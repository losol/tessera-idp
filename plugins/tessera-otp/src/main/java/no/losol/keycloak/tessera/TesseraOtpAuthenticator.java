// tessera-otp · passwordless one-time-code authenticator for Keycloak
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

package no.losol.keycloak.tessera;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.email.EmailSenderProvider;
import org.keycloak.email.EmailTemplateProvider;
import org.keycloak.locale.LocaleSelectorProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.SingleUseObjectProvider;
import org.keycloak.models.utils.FormMessage;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.theme.Theme;

import org.altcha.altcha.v2.Altcha;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Passwordless one-time-code authenticator.
 *
 * Flow: user enters email → receives OTP → enters OTP → logged in.
 * The user account is only created once the OTP has been verified, so an
 * unproven address never becomes a permanent user.
 * Does not require a preceding username form — handles everything in one step.
 *
 * <h2>Cross-session OTP storage and throttling</h2>
 * The OTP (and its wrong-attempt counter) lives in Keycloak's
 * {@link SingleUseObjectProvider}, keyed per realm + email rather than in the
 * per-browser auth session. This means:
 * <ul>
 *   <li>A code emailed in one browser/tab can be verified from any session,
 *       improving UX (open the link/code on another device).</li>
 *   <li>Send rate-limiting is enforced across sessions: a separate throttle
 *       key with a short TTL prevents a fresh submit (even from a new session)
 *       from triggering another email while a valid code is still outstanding.</li>
 *   <li>Wrong-attempt counts are tracked per code, so brute-force attempts are
 *       capped regardless of how many sessions an attacker opens.</li>
 * </ul>
 * Only the email being verified is kept in the auth session
 * ({@link #AUTH_NOTE_EMAIL}) so the action handler knows which step it is on.
 *
 * <h2>Fixed review codes</h2>
 * A prepared account (an app store reviewer's, say) can be given a temporary
 * fixed code for one client, see {@link ReviewAccess}. Its record in the store
 * carries {@link #NOTE_REVIEW} and no code: step 1 sends no email, step 2
 * compares against the configured code and attaches the account before a
 * failure, so realm brute force protection counts wrong guesses against it.
 * Everything else (expiry, attempts, throttle) is the ordinary path.
 */
public class TesseraOtpAuthenticator implements Authenticator {
    private static final Logger LOG = Logger.getLogger(TesseraOtpAuthenticator.class);

    // The only auth-session note we still keep: which email this browser session
    // is currently verifying. The code/attempts live in SingleUseObjectProvider.
    private static final String AUTH_NOTE_EMAIL = "tessera-otp-email";

    private static final String EMAIL_FORM_FIELD = "email";
    private static final String OTP_FORM_FIELD = "otp-code";
    private static final String RESET_FORM_FIELD = "reset-email";
    private static final String START_TEMPLATE = "login-tessera-otp-start.ftl";
    private static final String OTP_TEMPLATE = "login-tessera-otp-code.ftl";

    // SingleUseObject note keys (inside the stored notes map).
    private static final String NOTE_CODE = "code";
    private static final String NOTE_ATTEMPTS = "attempts";
    private static final String NOTE_EXPIRES_AT = "expiresAt";
    // "true" on a record whose code is the configured fixed review code
    // (ReviewAccess) rather than an emailed one. The code itself is never stored.
    private static final String NOTE_REVIEW = "review";

    // Max wrong code entries before the code is invalidated and the user must
    // request a new one. Brute-force cap (constant-time compare aside).
    static final int MAX_ATTEMPTS = 5;

    // One email per address per this many seconds, enforced cross-session via
    // the throttle key's TTL.
    static final int SEND_THROTTLE_SECONDS = 120;

    // Pragmatic email shape check: a non-empty local part, an "@", and a
    // dotted domain, with no whitespace. This rejects obvious garbage; it is
    // not a sanitizer — output escaping in the theme is what prevents XSS.
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    static final String DEFAULT_ALPHABET = "0123456789";
    static final int DEFAULT_LENGTH = 6;
    static final int DEFAULT_LIFETIME_SECONDS = 300;

    // --- ALTCHA proof-of-work captcha -------------------------------------

    // Authenticator config key holding the HMAC secret. When blank/absent the
    // whole ALTCHA feature is OFF: no challenge is rendered and nothing is
    // verified, so the flow behaves exactly as it did before this feature and
    // smoke/dev keep working without a key.
    private static final String CONFIG_ALTCHA_KEY = "altcha-hmac-key";

    // Hidden form field the <altcha-widget> submits the base64 solution in.
    private static final String ALTCHA_FORM_FIELD = "altcha";

    // PBKDF2-based proof-of-work tuning. The cost is the number of hash
    // iterations the browser must brute-force; ~5000 is a few hundred ms of work
    // — enough friction for bots, invisible to humans. Configurable per realm
    // (`altcha-cost`); the cost is signed into each challenge, so changing it
    // only affects challenges issued afterwards.
    private static final String ALTCHA_ALGORITHM = "PBKDF2/SHA-256";
    private static final String CONFIG_ALTCHA_COST = "altcha-cost";
    static final int DEFAULT_ALTCHA_COST = 5000;
    // Challenge validity. A solution older than this is rejected as expired.
    private static final int ALTCHA_EXPIRES_SECONDS = 600;
    // Replay-guard TTL: a solved challenge id is remembered at least this long
    // (matches the challenge expiry) so the same solution cannot be reused.
    private static final int ALTCHA_REPLAY_TTL_SECONDS = 600;

    // Form attributes the start page reads (see KcContext / LoginTesseraOtpStart).
    private static final String FORM_ATTR_ALTCHA_CHALLENGE = "altchaChallenge";
    private static final String FORM_ATTR_ALTCHA_ENABLED = "altchaEnabled";

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        // Show email input form, with a fresh ALTCHA challenge when enabled.
        Response challenge = withAltchaChallenge(context, context.form()).createForm(START_TEMPLATE);
        context.challenge(challenge);
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        AuthenticationSessionModel session = context.getAuthenticationSession();

        // "Wrong email, go back": clear the pending email note and the
        // cross-session code/throttle state for that email, then re-show the
        // email form regardless of which step we are on. A review account
        // attached by a wrong fixed code is detached too, so the next address
        // does not collide with it.
        MultivaluedMap<String, String> formData = context.getHttpRequest().getDecodedFormParameters();
        if (formData.containsKey(RESET_FORM_FIELD)) {
            String pendingEmail = session.getAuthNote(AUTH_NOTE_EMAIL);
            if (pendingEmail != null) {
                clearOtpState(context, pendingEmail);
            }
            session.removeAuthNote(AUTH_NOTE_EMAIL);
            context.clearUser();
            Response challenge = withAltchaChallenge(context, context.form()).createForm(START_TEMPLATE);
            context.challenge(challenge);
            return;
        }

        String pendingEmail = session.getAuthNote(AUTH_NOTE_EMAIL);

        if (pendingEmail == null) {
            // Step 1: user submitted email
            handleEmailSubmission(context);
        } else {
            // Step 2: user submitted OTP code
            handleOtpSubmission(context, pendingEmail);
        }
    }

    private void handleEmailSubmission(AuthenticationFlowContext context) {
        MultivaluedMap<String, String> formData = context.getHttpRequest().getDecodedFormParameters();

        // ALTCHA gate (only when an HMAC key is configured). Verify the proof of
        // work BEFORE validating the email or sending anything, so bots cannot
        // trigger OTP mail without first solving the challenge. On any failure
        // the start page is re-shown with a FRESH challenge (the old one is
        // single-use / may now be a replay).
        if (isAltchaEnabled(context)) {
            String solution = formData.getFirst(ALTCHA_FORM_FIELD);
            if (solution == null || solution.isBlank()) {
                Response challenge = withAltchaChallenge(context, context.form())
                        .setError("altchaRequired")
                        .createForm(START_TEMPLATE);
                context.challenge(challenge);
                return;
            }
            if (!verifyAltcha(context, solution)) {
                Response challenge = withAltchaChallenge(context, context.form())
                        .setError("altchaFailed")
                        .createForm(START_TEMPLATE);
                context.challenge(challenge);
                return;
            }
        }

        String email = formData.getFirst(EMAIL_FORM_FIELD);

        if (email == null || email.isBlank() || !EMAIL_PATTERN.matcher(email.trim()).matches()) {
            // Field-level error (not a global one) so the email form re-opens with
            // the message shown inline under the input, instead of collapsing.
            // Re-issue a challenge too so the captcha is solvable on retry.
            Response challenge = withAltchaChallenge(context, context.form())
                    .addError(new FormMessage(EMAIL_FORM_FIELD, "tesseraOtpInvalidEmail"))
                    .createForm(START_TEMPLATE);
            context.challenge(challenge);
            return;
        }

        email = email.trim().toLowerCase();

        // Look up the user, but do NOT create one yet — an account is only
        // created once the OTP has proven ownership of the address (step 2).
        UserModel user = findUserByEmail(context.getSession(), context.getRealm(), email);

        // A prepared review account gets no email: step 2 checks its fixed
        // code instead. Anything not fully in order falls back to the
        // ordinary emailed code (see reviewCodeFor).
        boolean review = reviewCodeFor(context, email, user) != null;

        AuthenticationSessionModel session = context.getAuthenticationSession();
        SingleUseObjectProvider store = context.getSession().getProvider(SingleUseObjectProvider.class);
        String codeKey = codeKey(context, email);
        String throttleKey = throttleKey(context, email);

        // Send rate-limit: if a still-valid code already exists for this address
        // AND we are inside the throttle window, do NOT send another email. The
        // previously emailed code remains valid; just show the code page again.
        // This stops repeated submits (same or new session) from flooding mail,
        // while a legitimate reload still lands on a working code page.
        Map<String, String> existing = store.get(codeKey);
        boolean validCodeExists = existing != null && !isStoredCodeExpired(existing);
        if (validCodeExists && store.contains(throttleKey)) {
            session.setAuthNote(AUTH_NOTE_EMAIL, email);
            // Tell the user why no new mail was sent — the earlier code still works.
            Response challenge = context.form()
                    .setAttribute("sentToEmail", email)
                    .setInfo("tesseraOtpThrottled")
                    .createForm(OTP_TEMPLATE);
            context.challenge(challenge);
            return;
        }

        // Otherwise generate, store and send a fresh code, and arm the throttle.
        // A review record stores no code at all; the fixed one stays in config.
        int lifetime = getConfigInt(context, "code-lifetime", DEFAULT_LIFETIME_SECONDS);
        String otp = review ? "" : generateOtp(context);
        long expiresAt = System.currentTimeMillis() / 1000 + lifetime;
        putCode(store, codeKey, otp, 0, expiresAt, lifetime, review);
        armThrottle(store, throttleKey);
        session.setAuthNote(AUTH_NOTE_EMAIL, email);

        try {
            if (!review) {
                sendOtp(context, user, email, otp);
            }
        } catch (Exception e) {
            context.failureChallenge(AuthenticationFlowError.INTERNAL_ERROR,
                    context.form().setError("tesseraOtpSendFailed").createErrorPage(Response.Status.INTERNAL_SERVER_ERROR));
            return;
        }

        Response challenge = context.form()
                .setAttribute("sentToEmail", email)
                .createForm(OTP_TEMPLATE);
        context.challenge(challenge);
    }

    private void handleOtpSubmission(AuthenticationFlowContext context, String pendingEmail) {
        MultivaluedMap<String, String> formData = context.getHttpRequest().getDecodedFormParameters();
        String enteredOtp = formData.getFirst(OTP_FORM_FIELD);

        SingleUseObjectProvider store = context.getSession().getProvider(SingleUseObjectProvider.class);
        String codeKey = codeKey(context, pendingEmail);

        if (enteredOtp == null || enteredOtp.isBlank()) {
            Response challenge = context.form()
                    .setAttribute("sentToEmail", pendingEmail)
                    .setError("tesseraOtpRequired")
                    .createForm(OTP_TEMPLATE);
            context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, challenge);
            return;
        }

        Map<String, String> stored = store.get(codeKey);

        // No code, or the original expiry passed: the user must request a new
        // one. The throttle naturally lapses with the code so they can resubmit
        // the email (or use the "go back" link).
        if (stored == null || isStoredCodeExpired(stored)) {
            clearOtpState(context, pendingEmail);
            Response challenge = context.form()
                    .setAttribute("sentToEmail", pendingEmail)
                    .setError("tesseraOtpExpired")
                    .createForm(OTP_TEMPLATE);
            context.failureChallenge(AuthenticationFlowError.EXPIRED_CODE, challenge);
            return;
        }

        // A review record compares against the fixed code as configured NOW,
        // so removing, rotating or expiring the config takes effect on an
        // outstanding challenge too; the account must still qualify.
        boolean review = "true".equals(stored.get(NOTE_REVIEW));
        UserModel reviewUser = review ? findUserByEmail(context.getSession(), context.getRealm(), pendingEmail) : null;
        String expectedOtp = review ? reviewCodeFor(context, pendingEmail, reviewUser) : stored.get(NOTE_CODE);
        int attempts = parseIntSafe(stored.get(NOTE_ATTEMPTS), 0);
        long expiresAt = parseLongSafe(stored.get(NOTE_EXPIRES_AT), 0);

        // No code to compare against: a partial/corrupt store entry, or a
        // review record whose fixed code no longer applies. Treat it as
        // expired and clear, so the user starts over at the email step.
        if (expectedOtp == null) {
            clearOtpState(context, pendingEmail);
            Response challenge = context.form()
                    .setAttribute("sentToEmail", pendingEmail)
                    .setError("tesseraOtpExpired")
                    .createForm(OTP_TEMPLATE);
            context.failureChallenge(AuthenticationFlowError.EXPIRED_CODE, challenge);
            return;
        }

        if (review) {
            // Attach the account before any failure below, so realm brute force
            // protection counts a wrong fixed code against it like a wrong
            // password. The user was checked for enabled/locked in reviewCodeFor.
            context.setUser(reviewUser);
        }

        // Count this attempt up-front. Once the cap is exceeded, invalidate the
        // code entirely so neither this nor any other session can keep guessing.
        // The read-modify-write is best-effort under heavy concurrency
        // (SingleUseObjectProvider has no atomic increment); brute-force is
        // bounded primarily by the 6-digit space + short expiry + the send
        // rate-limit (codes can't be minted freely) + realm bruteForceProtected,
        // with this per-code counter as a secondary cap.
        attempts++;
        if (attempts > MAX_ATTEMPTS) {
            clearOtpState(context, pendingEmail);
            Response challenge = context.form()
                    .setAttribute("sentToEmail", pendingEmail)
                    .setError("tesseraOtpTooManyAttempts")
                    .createForm(OTP_TEMPLATE);
            context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, challenge);
            return;
        }

        // Constant-time comparison. Codes are stored as generated; normalise the
        // entered value to upper-case to keep alphanumeric alphabets forgiving
        // (numeric default is unaffected).
        if (!MessageDigest.isEqual(
                enteredOtp.trim().toUpperCase().getBytes(StandardCharsets.UTF_8),
                expectedOtp.getBytes(StandardCharsets.UTF_8))) {
            // Persist the incremented count, preserving the ORIGINAL expiry so the
            // re-put does not extend the code's lifetime.
            long remaining = expiresAt - System.currentTimeMillis() / 1000;
            if (remaining <= 0) {
                clearOtpState(context, pendingEmail);
                Response challenge = context.form()
                        .setAttribute("sentToEmail", pendingEmail)
                        .setError("tesseraOtpExpired")
                        .createForm(OTP_TEMPLATE);
                context.failureChallenge(AuthenticationFlowError.EXPIRED_CODE, challenge);
                return;
            }
            putCode(store, codeKey, review ? "" : expectedOtp, attempts, expiresAt, remaining, review);
            Response challenge = context.form()
                    .setAttribute("sentToEmail", pendingEmail)
                    .setError("tesseraOtpInvalid")
                    .createForm(OTP_TEMPLATE);
            context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, challenge);
            return;
        }

        // Code verified — drop both the code and throttle keys so it cannot be
        // reused, then look up or create the account, marking the email verified
        // since the OTP proved ownership of the address.
        clearOtpState(context, pendingEmail);

        // A review account always exists (reviewCodeFor insisted on it); an
        // ordinary address may be a new user.
        UserModel user = review ? reviewUser : findUserByEmail(context.getSession(), context.getRealm(), pendingEmail);
        if (user == null) {
            try {
                user = context.getSession().users().addUser(context.getRealm(), pendingEmail);
                user.setEmail(pendingEmail);
                user.setEnabled(true);
            } catch (ModelDuplicateException e) {
                // A concurrent verification created the same account between the
                // lookup and addUser; re-fetch it instead of failing the login.
                user = findUserByEmail(context.getSession(), context.getRealm(), pendingEmail);
            }
        }
        if (user == null) {
            context.failureChallenge(AuthenticationFlowError.INTERNAL_ERROR,
                    context.form().setError("tesseraOtpSendFailed").createErrorPage(Response.Status.INTERNAL_SERVER_ERROR));
            return;
        }
        user.setEmailVerified(true);

        if (review) {
            // The audit trail for a bypass of the emailed code: who, where, for
            // which client. Never the code.
            LOG.infof("Fixed review code accepted for %s in realm %s, client %s", pendingEmail,
                    context.getRealm().getName(), clientId(context));
        }

        context.getAuthenticationSession().removeAuthNote(AUTH_NOTE_EMAIL);
        context.setUser(user);
        context.success();
    }

    // --- Fixed review codes --------------------------------------------------

    /**
     * The fixed review code for this address, or null when the ordinary emailed
     * code applies. {@link ReviewAccess} decides from the config; on top of that
     * the account must exist, be enabled and not be locked out, so a fixed code
     * never provisions a user or bypasses a lockout. A listed address that is
     * refused is logged with the reason (never the code), so a reviewer stuck
     * on a config mistake can be helped quickly.
     */
    private String reviewCodeFor(AuthenticationFlowContext context, String email, UserModel user) {
        ReviewAccess.Result result = ReviewAccess.resolve(
                context.getAuthenticatorConfig() == null ? null : context.getAuthenticatorConfig().getConfig(),
                email,
                clientId(context),
                context.getRealm().isBruteForceProtected(),
                Instant.now());
        if (result.decision() == ReviewAccess.Decision.OFF
                || result.decision() == ReviewAccess.Decision.NOT_A_REVIEW_ACCOUNT) {
            return null;
        }

        String refusal = null;
        if (!result.granted()) {
            refusal = result.decision().name();
        } else if (user == null || !user.isEnabled()) {
            refusal = "NO_ENABLED_USER";
        } else if (isLockedOut(context, user)) {
            refusal = "LOCKED_OUT";
        }
        if (refusal != null) {
            LOG.warnf("Fixed review code not used for %s (%s); the ordinary emailed code applies", email, refusal);
            return null;
        }
        return result.code();
    }

    private boolean isLockedOut(AuthenticationFlowContext context, UserModel user) {
        var protector = context.getProtector();
        return protector.isTemporarilyDisabled(context.getSession(), context.getRealm(), user)
                || protector.isPermanentlyLockedOut(context.getSession(), context.getRealm(), user);
    }

    private String clientId(AuthenticationFlowContext context) {
        var client = context.getAuthenticationSession().getClient();
        return client == null ? null : client.getClientId();
    }

    private UserModel findUserByEmail(KeycloakSession session, RealmModel realm, String email) {
        // Look up by the standard email field first (covers users created via
        // registration or identity-provider brokering, where the address lives
        // on UserModel.email rather than a custom attribute). Fall back to
        // username for realms that use email-as-username. Searching a custom
        // "email" attribute missed these users, causing a duplicate-key error
        // when the authenticator then tried to re-create an existing user.
        UserModel user = session.users().getUserByEmail(realm, email);
        if (user == null) {
            user = session.users().getUserByUsername(realm, email);
        }
        return user;
    }

    private String generateOtp(AuthenticationFlowContext context) {
        int length = getConfigInt(context, "code-length", DEFAULT_LENGTH);
        String alphabet = getConfigString(context, "code-alphabet", DEFAULT_ALPHABET);

        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    // --- ALTCHA helpers ----------------------------------------------------

    /** The configured proof-of-work cost; a missing or non-positive value uses the default. */
    private int altchaCost(AuthenticationFlowContext context) {
        int cost = getConfigInt(context, CONFIG_ALTCHA_COST, DEFAULT_ALTCHA_COST);
        return cost > 0 ? cost : DEFAULT_ALTCHA_COST;
    }

    /** ALTCHA is enabled only when a non-blank HMAC key is configured. */
    private boolean isAltchaEnabled(AuthenticationFlowContext context) {
        return !getConfigString(context, CONFIG_ALTCHA_KEY, "").isBlank();
    }

    /**
     * When ALTCHA is enabled, generate a fresh challenge and attach it (as JSON)
     * to the form so the start page can hand it to the &lt;altcha-widget&gt; in
     * self-contained mode. When disabled this is a no-op and the page renders
     * with no widget. Returns the same form for fluent chaining.
     */
    private org.keycloak.forms.login.LoginFormsProvider withAltchaChallenge(
            AuthenticationFlowContext context, org.keycloak.forms.login.LoginFormsProvider form) {
        if (!isAltchaEnabled(context)) {
            return form;
        }
        String key = getConfigString(context, CONFIG_ALTCHA_KEY, "");
        try {
            Altcha.Challenge challenge = Altcha.createChallenge(
                    new Altcha.CreateChallengeOptions()
                            .algorithm(ALTCHA_ALGORITHM)
                            .cost(altchaCost(context))
                            .hmacSignatureSecret(key)
                            .expiresInSeconds(ALTCHA_EXPIRES_SECONDS));
            form.setAttribute(FORM_ATTR_ALTCHA_CHALLENGE, challenge.toJson());
            form.setAttribute(FORM_ATTR_ALTCHA_ENABLED, "true");
        } catch (Exception e) {
            // If challenge creation fails we cannot render a solvable captcha.
            // Surface it as an error rather than silently letting the gate
            // through (the email submit would then always fail verification).
            throw new RuntimeException("Failed to create ALTCHA challenge", e);
        }
        return form;
    }

    /**
     * Verifies the submitted ALTCHA solution against the configured HMAC key and
     * enforces single use (replay protection). Returns true only for a fresh,
     * valid, non-expired, correctly-signed solution.
     */
    private boolean verifyAltcha(AuthenticationFlowContext context, String base64Payload) {
        String key = getConfigString(context, CONFIG_ALTCHA_KEY, "");
        try {
            Altcha.VerifySolutionResult result =
                    Altcha.verifySolution(base64Payload, key, Altcha.kdf(ALTCHA_ALGORITHM));
            if (!result.verified() || result.expired()) {
                return false;
            }

            // Replay protection: a valid solution may only be spent once. Derive
            // a stable id from the solved challenge (its salt is unique per
            // challenge and present in the decoded payload) and record it in the
            // single-use store. A second submission of the same solution finds
            // the id already present and is treated as failed.
            String replayId = altchaReplayId(base64Payload);
            if (replayId != null) {
                SingleUseObjectProvider store =
                        context.getSession().getProvider(SingleUseObjectProvider.class);
                String replayKey = "tessera-otp:altcha:" + replayId;
                if (store.contains(replayKey)) {
                    return false; // already spent — replay
                }
                store.put(replayKey, ALTCHA_REPLAY_TTL_SECONDS, new HashMap<>());
            }
            return true;
        } catch (Exception e) {
            // Malformed/undecodable payload — treat as a failed solution.
            return false;
        }
    }

    /** Extracts a stable per-challenge id (the salt) from the decoded payload. */
    private String altchaReplayId(String base64Payload) {
        try {
            Altcha.Payload payload = Altcha.parsePayload(base64Payload);
            return payload.challenge().parameters().salt();
        } catch (Exception e) {
            return null;
        }
    }

    // --- SingleUseObject helpers -------------------------------------------

    // Per-realm + per-email key for the code+attempts record.
    private String codeKey(AuthenticationFlowContext context, String email) {
        return "tessera-otp:code:" + context.getRealm().getName() + ":" + email;
    }

    // Per-realm + per-email key whose mere presence (with a 120s TTL) means an
    // email was sent recently and we must not send another yet.
    private String throttleKey(AuthenticationFlowContext context, String email) {
        return "tessera-otp:throttle:" + context.getRealm().getName() + ":" + email;
    }

    /**
     * Stores (or re-stores) the code record. The SingleUseObjectProvider may
     * reject a put for an existing key, so we always remove first. {@code lifespan}
     * is what the store uses for its own TTL; the authoritative expiry is the
     * {@code expiresAt} note so attempt re-puts never extend the real lifetime.
     */
    private void putCode(SingleUseObjectProvider store, String key, String code,
                         int attempts, long expiresAt, long lifespan, boolean review) {
        Map<String, String> notes = new HashMap<>();
        notes.put(NOTE_CODE, code);
        notes.put(NOTE_ATTEMPTS, String.valueOf(attempts));
        notes.put(NOTE_EXPIRES_AT, String.valueOf(expiresAt));
        if (review) {
            notes.put(NOTE_REVIEW, "true");
        }
        store.remove(key);
        store.put(key, Math.max(1, lifespan), notes);
    }

    private void armThrottle(SingleUseObjectProvider store, String key) {
        store.remove(key);
        store.put(key, SEND_THROTTLE_SECONDS, new HashMap<>());
    }

    // Clears all cross-session OTP state for the email: the code record and the
    // send throttle. Used on success, attempt-cap, expiry and "wrong email".
    private void clearOtpState(AuthenticationFlowContext context, String email) {
        SingleUseObjectProvider store = context.getSession().getProvider(SingleUseObjectProvider.class);
        store.remove(codeKey(context, email));
        store.remove(throttleKey(context, email));
    }

    private boolean isStoredCodeExpired(Map<String, String> notes) {
        long expiresAt = parseLongSafe(notes.get(NOTE_EXPIRES_AT), 0);
        return System.currentTimeMillis() / 1000 >= expiresAt;
    }

    private int parseIntSafe(String value, int defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private long parseLongSafe(String value, long defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Sends the OTP to the given address. For an existing, persisted user the
     * standard email template provider is used (so the message renders with the
     * realm's email theme/branding). When the user does not exist yet the OTP is
     * sent straight to the raw address via the email sender provider, because
     * EmailTemplateProvider.setUser(...) requires a persisted user.
     */
    private void sendOtp(AuthenticationFlowContext context, UserModel user, String address, String otp) throws Exception {
        int lifetime = getConfigInt(context, "code-lifetime", DEFAULT_LIFETIME_SECONDS);

        if (user != null) {
            Map<String, Object> attrs = new HashMap<>();
            attrs.put("otp", otp);
            attrs.put("ttl", lifetime);
            attrs.put("ttlMinutes", lifetime / 60);

            context.getSession()
                    .getProvider(EmailTemplateProvider.class)
                    .setRealm(context.getRealm())
                    .setUser(user)
                    .send("tesseraOtpSubject", "tessera-otp-code-email.ftl", attrs);
            return;
        }

        sendOtpToAddress(context, address, otp, lifetime / 60);
    }

    /**
     * Sends the OTP directly to a raw address with no persisted user. The
     * subject and body are built from the same message keys the
     * tessera-otp-code-email.ftl template uses (tesseraOtpSubject,
     * tesseraOtpBody, tesseraOtpExpiration),
     * resolved against the realm's email theme for the requested locale.
     */
    private void sendOtpToAddress(AuthenticationFlowContext context, String address, String otp, int ttlMinutes)
            throws Exception {
        KeycloakSession session = context.getSession();
        RealmModel realm = context.getRealm();

        Locale locale = session.getProvider(LocaleSelectorProvider.class).resolveLocale(realm, null);
        Theme theme = session.theme().getTheme(Theme.Type.EMAIL);
        Properties messages = theme.getEnhancedMessages(realm, locale);

        String subject = messages.getProperty("tesseraOtpSubject", "Your login code");
        String bodyIntro = messages.getProperty("tesseraOtpBody", "Here is your one-time login code:");
        String expiration = MessageFormat.format(
                messages.getProperty("tesseraOtpExpiration", "This code is valid for {0} minutes."),
                ttlMinutes);

        String textBody = bodyIntro + "\n\n" + otp + "\n\n" + expiration + "\n";
        String htmlBody = "<html><body>"
                + "<p>" + escapeHtml(bodyIntro) + "</p>"
                + "<h1 style=\"font-family: monospace; letter-spacing: 0.3em; font-size: 2em;\">"
                + escapeHtml(otp) + "</h1>"
                + "<p>" + escapeHtml(expiration) + "</p>"
                + "</body></html>";

        session.getProvider(EmailSenderProvider.class)
                .send(realm.getSmtpConfig(), address, subject, textBody, htmlBody);
    }

    private String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private int getConfigInt(AuthenticationFlowContext context, String key, int defaultValue) {
        if (context.getAuthenticatorConfig() == null) return defaultValue;
        String val = context.getAuthenticatorConfig().getConfig().get(key);
        if (val == null || val.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String getConfigString(AuthenticationFlowContext context, String key, String defaultValue) {
        if (context.getAuthenticatorConfig() == null) return defaultValue;
        String val = context.getAuthenticatorConfig().getConfig().get(key);
        return (val == null || val.isBlank()) ? defaultValue : val;
    }

    @Override
    public boolean requiresUser() {
        return false;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
    }

    @Override
    public void close() {
    }
}
