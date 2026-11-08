// tessera-otp · passwordless one-time-code authenticator for Keycloak
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

package no.losol.keycloak.tessera;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.email.EmailSenderProvider;
import org.keycloak.email.EmailTemplateProvider;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.http.HttpRequest;
import org.keycloak.locale.LocaleSelectorProvider;
import org.keycloak.models.*;
import org.keycloak.services.managers.BruteForceProtector;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.theme.Theme;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the real authenticator through both steps with a review account, on
 * hand-rolled proxies of the Keycloak interfaces it touches. Every proxy fails
 * on a method the scenario should not need, so an unexpected side effect (a
 * user created, an email sent) is an error, not a silent pass.
 */
class TesseraOtpReviewTest {

    private static final String EMAIL = "appreview@example.org";
    private static final String CODE = "48215093";
    private static final String CLIENT = "civitas-mobile";

    private final TesseraOtpAuthenticator authenticator = new TesseraOtpAuthenticator();
    private final AuthenticatorConfigModel config = new AuthenticatorConfigModel();
    private final Map<String, Map<String, String>> store = new HashMap<>();
    private final Map<String, String> authNotes = new HashMap<>();
    private final Map<String, Object> formAttributes = new HashMap<>();
    private final List<String> emailsSent = new ArrayList<>();
    private final List<LogRecord> logs = new ArrayList<>();

    private MultivaluedMap<String, String> fields = new MultivaluedHashMap<>();
    private boolean userExists = true;
    private boolean userEnabled = true;
    private boolean lockedOut;
    private boolean bruteForceProtected = true;
    private String clientId = CLIENT;
    private String formError;
    private AuthenticationFlowError failure;
    private UserModel attachedUser;
    private UserModel userAtFailure;
    private boolean succeeded;

    private UserModel user;
    private AuthenticationFlowContext context;

    private final Logger authenticatorLog = Logger.getLogger(TesseraOtpAuthenticator.class.getName());
    private final Handler capture = new Handler() {
        public void publish(LogRecord record) { logs.add(record); }
        public void flush() {}
        public void close() {}
    };

    @BeforeEach
    void setUp() {
        store.clear();
        authNotes.clear();
        formAttributes.clear();
        emailsSent.clear();
        logs.clear();
        userExists = true;
        userEnabled = true;
        lockedOut = false;
        bruteForceProtected = true;
        clientId = CLIENT;
        formError = null;
        failure = null;
        attachedUser = null;
        userAtFailure = null;
        succeeded = false;
        authenticatorLog.removeHandler(capture);
        authenticatorLog.addHandler(capture);
        config.setConfig(new HashMap<>(Map.of(
                ReviewAccess.CONFIG_ACCOUNTS, EMAIL + "=" + CODE,
                ReviewAccess.CONFIG_CLIENTS, CLIENT,
                ReviewAccess.CONFIG_VALID_UNTIL, LocalDate.now(ZoneOffset.UTC).plusDays(7).toString())));

        user = proxy(UserModel.class, (p, m, a) -> switch (m.getName()) {
            case "isEnabled" -> userEnabled;
            case "getEmail" -> EMAIL;
            case "getId" -> "review-user-id";
            case "setEmailVerified" -> null;
            default -> throw new AssertionError("UserModel." + m.getName());
        });
        UserProvider users = proxy(UserProvider.class, (p, m, a) -> switch (m.getName()) {
            case "getUserByEmail", "getUserByUsername" -> userExists ? user : null;
            // A review login must never create an account.
            default -> throw new AssertionError("UserProvider." + m.getName());
        });
        RealmModel realm = proxy(RealmModel.class, (p, m, a) -> switch (m.getName()) {
            case "getName" -> "test-realm";
            case "isBruteForceProtected" -> bruteForceProtected;
            case "getSmtpConfig" -> Map.of();
            default -> throw new AssertionError("RealmModel." + m.getName());
        });
        SingleUseObjectProvider singleUse = proxy(SingleUseObjectProvider.class, (p, m, a) -> switch (m.getName()) {
            case "get" -> store.get(a[0]);
            case "remove" -> store.remove(a[0]);
            case "contains" -> store.containsKey(a[0]);
            case "put" -> {
                @SuppressWarnings("unchecked") Map<String, String> notes = (Map<String, String>) a[2];
                store.put((String) a[0], new HashMap<>(notes));
                yield null;
            }
            default -> throw new AssertionError("SingleUseObjectProvider." + m.getName());
        });
        // Emailed codes for an existing user go through the template provider;
        // for an address without a user, straight to the sender. Both record
        // the code so the test can redeem it.
        EmailTemplateProvider mail = proxy(EmailTemplateProvider.class, (p, m, a) -> switch (m.getName()) {
            case "setRealm", "setUser" -> p;
            case "send" -> { emailsSent.add((String) ((Map<?, ?>) a[2]).get("otp")); yield null; }
            default -> throw new AssertionError("EmailTemplateProvider." + m.getName());
        });
        EmailSenderProvider sender = proxy(EmailSenderProvider.class, (p, m, a) -> switch (m.getName()) {
            case "send" -> { emailsSent.add(((String) a[3]).split("\n\n")[1]); yield null; }
            default -> throw new AssertionError("EmailSenderProvider." + m.getName());
        });
        LocaleSelectorProvider locales = proxy(LocaleSelectorProvider.class, (p, m, a) -> switch (m.getName()) {
            case "resolveLocale" -> Locale.ENGLISH;
            default -> throw new AssertionError("LocaleSelectorProvider." + m.getName());
        });
        Theme theme = proxy(Theme.class, (p, m, a) -> switch (m.getName()) {
            case "getEnhancedMessages" -> new Properties();
            default -> throw new AssertionError("Theme." + m.getName());
        });
        ThemeManager themes = proxy(ThemeManager.class, (p, m, a) -> switch (m.getName()) {
            case "getTheme" -> theme;
            default -> throw new AssertionError("ThemeManager." + m.getName());
        });
        KeycloakSession session = proxy(KeycloakSession.class, (p, m, a) -> switch (m.getName()) {
            case "users" -> users;
            case "theme" -> themes;
            case "getProvider" -> {
                if (a[0] == SingleUseObjectProvider.class) yield singleUse;
                if (a[0] == EmailTemplateProvider.class) yield mail;
                if (a[0] == EmailSenderProvider.class) yield sender;
                if (a[0] == LocaleSelectorProvider.class) yield locales;
                throw new AssertionError("provider " + a[0]);
            }
            default -> throw new AssertionError("KeycloakSession." + m.getName());
        });
        ClientModel client = proxy(ClientModel.class, (p, m, a) -> switch (m.getName()) {
            case "getClientId" -> clientId;
            default -> throw new AssertionError("ClientModel." + m.getName());
        });
        AuthenticationSessionModel authSession = proxy(AuthenticationSessionModel.class, (p, m, a) -> switch (m.getName()) {
            case "getAuthNote" -> authNotes.get(a[0]);
            case "setAuthNote" -> authNotes.put((String) a[0], (String) a[1]);
            case "removeAuthNote" -> authNotes.remove(a[0]);
            case "getClient" -> client;
            default -> throw new AssertionError("AuthenticationSessionModel." + m.getName());
        });
        LoginFormsProvider form = proxy(LoginFormsProvider.class, (p, m, a) -> switch (m.getName()) {
            case "setAttribute" -> { formAttributes.put((String) a[0], a[1]); yield p; }
            case "setError", "setInfo" -> { formError = (String) a[0]; yield p; }
            case "addError" -> p;
            case "createForm", "createErrorPage" -> Response.ok().build();
            default -> throw new AssertionError("LoginFormsProvider." + m.getName());
        });
        HttpRequest request = proxy(HttpRequest.class, (p, m, a) -> switch (m.getName()) {
            case "getDecodedFormParameters" -> fields;
            default -> throw new AssertionError("HttpRequest." + m.getName());
        });
        BruteForceProtector protector = proxy(BruteForceProtector.class, (p, m, a) -> switch (m.getName()) {
            case "isTemporarilyDisabled" -> lockedOut;
            case "isPermanentlyLockedOut" -> false;
            default -> throw new AssertionError("BruteForceProtector." + m.getName());
        });
        context = proxy(AuthenticationFlowContext.class, (p, m, a) -> switch (m.getName()) {
            case "getAuthenticatorConfig" -> config;
            case "getRealm" -> realm;
            case "getSession" -> session;
            case "getAuthenticationSession" -> authSession;
            case "getHttpRequest" -> request;
            case "getProtector" -> protector;
            case "form" -> form;
            case "setUser" -> { attachedUser = (UserModel) a[0]; yield null; }
            case "clearUser" -> { attachedUser = null; yield null; }
            case "challenge" -> { failure = null; yield null; }
            case "failureChallenge" -> { failure = (AuthenticationFlowError) a[0]; userAtFailure = attachedUser; yield null; }
            case "success" -> { succeeded = true; yield null; }
            default -> throw new AssertionError("AuthenticationFlowContext." + m.getName());
        });
    }

    @AfterEach
    void theCodeNeverLeaksIntoLogsFormsOrTheStore() {
        authenticatorLog.removeHandler(capture);
        for (LogRecord log : logs) {
            assertFalse(String.valueOf(log.getMessage()).contains(CODE), log.getMessage());
            for (Object parameter : Optional.ofNullable(log.getParameters()).orElse(new Object[0])) {
                assertFalse(String.valueOf(parameter).contains(CODE));
            }
        }
        assertFalse(formAttributes.toString().contains(CODE));
        assertFalse(store.toString().contains(CODE));
    }

    @Test
    void reviewAccountSignsInWithTheFixedCodeAndNoEmail() {
        submit("email", " APPREVIEW@Example.org ");
        assertTrue(emailsSent.isEmpty(), "no email for a review account");
        assertEquals(EMAIL, formAttributes.get("sentToEmail"));
        assertEquals("true", codeRecord().get("review"));
        assertEquals("", codeRecord().get("code"), "the fixed code is not stored");

        submit("otp-code", CODE);
        assertTrue(succeeded);
        assertSame(user, attachedUser);
        assertTrue(store.isEmpty(), "code and throttle records are cleared on success");
        assertTrue(logs.stream().anyMatch(l -> l.getLevel().intValue() >= Level.INFO.intValue()
                && l.getMessage().contains("Fixed review code accepted") && l.getMessage().contains(EMAIL)
                && l.getMessage().contains(CLIENT)), "an audit line names the address and the client");
    }

    @Test
    void aWrongFixedCodeIsCountedAgainstTheAccount() {
        submit("email", EMAIL);
        submit("otp-code", "00000000");
        assertFalse(succeeded);
        assertEquals(AuthenticationFlowError.INVALID_CREDENTIALS, failure);
        assertSame(user, userAtFailure, "the account is attached before the failure, so brute force protection counts it");
        assertEquals("tesseraOtpInvalid", formError);
        assertEquals("1", codeRecord().get("attempts"));

        // The cap works as for an emailed code: the record is dropped, a new
        // one has to be requested, and every guess so far counted against the user.
        for (int i = 1; i < TesseraOtpAuthenticator.MAX_ATTEMPTS; i++) submit("otp-code", "00000000");
        assertEquals(String.valueOf(TesseraOtpAuthenticator.MAX_ATTEMPTS), codeRecord().get("attempts"));
        submit("otp-code", "00000000");
        assertEquals("tesseraOtpTooManyAttempts", formError);
        assertTrue(store.isEmpty());
        assertFalse(succeeded);
    }

    @Test
    void theRightCodeStillWorksAfterWrongGuesses() {
        submit("email", EMAIL);
        submit("otp-code", "00000000");
        submit("otp-code", CODE);
        assertTrue(succeeded);
    }

    @Test
    void goingBackDetachesTheAccount() {
        submit("email", EMAIL);
        submit("otp-code", "00000000");
        assertSame(user, attachedUser);
        submit("reset-email", "true");
        assertNull(attachedUser);
        assertNull(authNotes.get("tessera-otp-email"));
        assertTrue(store.isEmpty());
    }

    @Test
    void removingOrRotatingTheConfigAppliesToAnOutstandingChallenge() {
        submit("email", EMAIL);
        config.getConfig().put(ReviewAccess.CONFIG_ACCOUNTS, EMAIL + "=99999999");
        submit("otp-code", CODE);
        assertFalse(succeeded);
        assertEquals(AuthenticationFlowError.INVALID_CREDENTIALS, failure);
        submit("otp-code", "99999999");
        assertTrue(succeeded);

        succeeded = false;
        authNotes.clear();
        submit("email", EMAIL);
        config.getConfig().remove(ReviewAccess.CONFIG_ACCOUNTS);
        submit("otp-code", "99999999");
        assertFalse(succeeded);
        assertEquals(AuthenticationFlowError.EXPIRED_CODE, failure);
        assertEquals("tesseraOtpExpired", formError);
        assertTrue(store.isEmpty(), "the stale review record is cleared; the next email step is ordinary");
    }

    @Test
    void fallsBackToTheEmailedCodeWhenTheReviewAccountDoesNotQualify() {
        record Case(String name, Runnable arrange) {}
        List<Case> cases = List.of(
                new Case("valid-until passed", () -> config.getConfig().put(ReviewAccess.CONFIG_VALID_UNTIL, "2020-01-01")),
                new Case("another client", () -> clientId = "account-console"),
                new Case("realm without brute force protection", () -> bruteForceProtected = false),
                new Case("no such user", () -> userExists = false),
                new Case("disabled user", () -> userEnabled = false),
                new Case("locked out user", () -> lockedOut = true));
        for (Case c : cases) {
            setUp(); // a clean slate per case
            c.arrange.run();
            submit("email", EMAIL);
            assertNull(failure, c.name);
            assertNull(attachedUser, c.name);
            assertEquals(1, emailsSent.size(), c.name + ": an ordinary code is emailed instead");
            assertNull(codeRecord().get("review"), c.name);
            assertTrue(logs.stream().anyMatch(l -> String.valueOf(l.getMessage()).contains("Fixed review code not used")),
                    c.name + ": the refusal is logged");
            // The fixed code does not verify an emailed challenge.
            submit("otp-code", CODE);
            assertFalse(succeeded, c.name);
        }
    }

    @Test
    void ordinaryAddressesAreUntouched() {
        submit("email", "someone@example.org");
        assertEquals(1, emailsSent.size());
        assertNull(codeRecord().get("review"));
        assertTrue(logs.isEmpty(), "no review logging for ordinary logins");
        submit("otp-code", CODE);
        assertFalse(succeeded);
        submit("otp-code", emailsSent.get(0));
        assertTrue(succeeded);
    }

    @Test
    void withoutTheSettingsNothingChanges() {
        config.setConfig(new HashMap<>());
        submit("email", EMAIL);
        assertEquals(1, emailsSent.size());
        assertTrue(logs.isEmpty());
    }

    private void submit(String field, String value) {
        fields = new MultivaluedHashMap<>();
        fields.putSingle(field, value);
        authenticator.action(context);
    }

    private Map<String, String> codeRecord() {
        return store.entrySet().stream()
                .filter(e -> e.getKey().startsWith("tessera-otp:code:"))
                .findFirst().orElseThrow(() -> new AssertionError("no code record in " + store.keySet())).getValue();
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
