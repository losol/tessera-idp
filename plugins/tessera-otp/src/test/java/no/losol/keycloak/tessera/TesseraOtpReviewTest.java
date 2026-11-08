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
import org.keycloak.email.EmailTemplateProvider;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.http.HttpRequest;
import org.keycloak.models.*;
import org.keycloak.services.managers.BruteForceProtector;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class TesseraOtpReviewTest {
    private static final String EMAIL = "appreview@example.org";
    private static final String CODE = "0123456789";
    private final TesseraOtpAuthenticator authenticator = new TesseraOtpAuthenticator();
    private final AuthenticatorConfigModel config = new AuthenticatorConfigModel();
    private final Map<String, Map<String, String>> store = new HashMap<>();
    private final Map<String, String> authNotes = new HashMap<>();
    private final Map<String, Object> attributes = new HashMap<>();
    private final List<String> emails = new ArrayList<>();
    private final List<LogRecord> logs = new ArrayList<>();
    private MultivaluedMap<String, String> fields = new MultivaluedHashMap<>();
    private boolean enabled = true;
    private boolean temporaryLock;
    private boolean permanentLock;
    private boolean exists = true;
    private boolean success;
    private String clientId = "civitas-mobile";
    private String userEmail = EMAIL;
    private String errorMessage;
    private AuthenticationFlowError failure;
    private UserModel attached;
    private UserModel failureUser;
    private UserModel user;
    private AuthenticationFlowContext context;
    private final Logger loginLogger = Logger.getLogger(TesseraOtpAuthenticator.class.getName());
    private final Logger configLogger = Logger.getLogger(ReviewAccounts.class.getName());
    private final Handler capture = new Handler() {
        public void publish(LogRecord record) { logs.add(record); }
        public void flush() {}
        public void close() {}
    };

    @BeforeEach
    void setUp() {
        loginLogger.addHandler(capture);
        configLogger.addHandler(capture);
        config.setConfig(new HashMap<>(Map.of(ReviewAccounts.ACCOUNTS, EMAIL + "=" + CODE)));
        user = proxy(UserModel.class, (p, m, a) -> switch (m.getName()) {
            case "isEnabled" -> enabled;
            case "getEmail" -> userEmail;
            case "setEmailVerified" -> { assertEquals(true, a[0]); yield null; }
            default -> throw new AssertionError(m);
        });
        UserProvider users = proxy(UserProvider.class, (p, m, a) -> switch (m.getName()) {
            case "getUserByEmail", "getUserByUsername" -> exists ? user : null;
            // Review mode must never provision an account.
            default -> throw new AssertionError(m);
        });
        RealmModel realm = proxy(RealmModel.class, (p, m, a) -> switch (m.getName()) {
            case "getName" -> "test-realm";
            case "isBruteForceProtected" -> true;
            default -> throw new AssertionError(m);
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
            default -> throw new AssertionError(m);
        });
        EmailTemplateProvider mail = proxy(EmailTemplateProvider.class, (p, m, a) -> switch (m.getName()) {
            case "setRealm", "setUser" -> p;
            case "send" -> {
                emails.add((String) ((Map<?, ?>) a[2]).get("otp"));
                yield null;
            }
            default -> throw new AssertionError(m);
        });
        KeycloakSession session = proxy(KeycloakSession.class, (p, m, a) -> switch (m.getName()) {
            case "users" -> users;
            case "getProvider" -> a[0] == SingleUseObjectProvider.class ? singleUse
                    : a[0] == EmailTemplateProvider.class ? mail : unexpectedProvider(a[0]);
            default -> throw new AssertionError(m);
        });
        ClientModel client = proxy(ClientModel.class, (p, m, a) -> {
            if (m.getName().equals("getClientId")) return clientId;
            throw new AssertionError(m);
        });
        AuthenticationSessionModel authSession = proxy(AuthenticationSessionModel.class, (p, m, a) -> switch (m.getName()) {
            case "getAuthNote" -> authNotes.get(a[0]);
            case "setAuthNote" -> authNotes.put((String) a[0], (String) a[1]);
            case "removeAuthNote" -> authNotes.remove(a[0]);
            case "getClient" -> client;
            default -> throw new AssertionError(m);
        });
        LoginFormsProvider form = proxy(LoginFormsProvider.class, (p, m, a) -> switch (m.getName()) {
            case "setAttribute" -> { attributes.put((String) a[0], a[1]); yield p; }
            case "setError", "setInfo" -> { errorMessage = (String) a[0]; yield p; }
            case "createForm", "createErrorPage" -> Response.ok().build();
            default -> throw new AssertionError(m);
        });
        HttpRequest request = proxy(HttpRequest.class, (p, m, a) -> {
            if (m.getName().equals("getDecodedFormParameters")) return fields;
            throw new AssertionError(m);
        });
        BruteForceProtector protector = proxy(BruteForceProtector.class, (p, m, a) -> switch (m.getName()) {
            case "isTemporarilyDisabled" -> temporaryLock;
            case "isPermanentlyLockedOut" -> permanentLock;
            default -> throw new AssertionError(m);
        });
        context = proxy(AuthenticationFlowContext.class, (p, m, a) -> switch (m.getName()) {
            case "getAuthenticatorConfig" -> config;
            case "getRealm" -> realm;
            case "getSession" -> session;
            case "getAuthenticationSession" -> authSession;
            case "getHttpRequest" -> request;
            case "getProtector" -> protector;
            case "form" -> form;
            case "setUser" -> { attached = (UserModel) a[0]; yield null; }
            case "clearUser" -> { attached = null; yield null; }
            case "challenge" -> null;
            case "failureChallenge" -> { failure = (AuthenticationFlowError) a[0]; failureUser = attached; yield null; }
            case "success" -> { success = true; yield null; }
            default -> throw new AssertionError(m);
        });
    }

    @AfterEach
    void tearDown() {
        loginLogger.removeHandler(capture);
        configLogger.removeHandler(capture);
        for (LogRecord log : logs) {
            assertFalse(log.getMessage().contains(CODE));
            assertNull(log.getThrown());
            if (log.getParameters() != null) {
                for (Object parameter : log.getParameters()) assertFalse(String.valueOf(parameter).contains(CODE));
            }
        }
        assertFalse(attributes.toString().contains(CODE));
        assertFalse(store.toString().contains(CODE));
    }

    @Test
    void reviewLoginSkipsAllEmailProvidersAndLogsOnlyTheAddress() {
        submit("email", " APPREVIEW@EXAMPLE.ORG ");
        assertTrue(emails.isEmpty());
        assertSame(user, attached);
        assertEquals(EMAIL, attributes.get("sentToEmail"));
        submit("otp-code", CODE);
        assertTrue(success);
        assertTrue(store.isEmpty());
        assertTrue(logs.stream().anyMatch(l -> l.getMessage().contains("Review-account login")
                && l.getMessage().contains(EMAIL)));
        // The credential can be used again in a new challenge, without a mailbox.
        success = false;
        submit("email", EMAIL);
        submit("otp-code", CODE);
        assertTrue(success);
    }

    @Test
    void ordinaryAddressesStillReceiveAndVerifyRandomCodes() {
        userEmail = "ordinary@example.org";
        submit("email", userEmail);
        assertEquals(1, emails.size());
        String generated = emails.get(0);
        assertEquals(6, generated.length());
        submit("otp-code", CODE);
        assertFalse(success);
        submit("otp-code", generated);
        assertTrue(success);
        assertTrue(logs.isEmpty());
    }

    @Test
    void absentBlankOrInvalidConfigUsesOrdinaryEmailFlow() {
        for (String setting : Arrays.asList(null, "", "  ", EMAIL + "=1234567")) {
            config.getConfig().put(ReviewAccounts.ACCOUNTS, setting);
            store.clear();
            authNotes.clear();
            submit("email", EMAIL);
            assertNull(attached);
        }
        assertEquals(4, emails.size());
        assertTrue(logs.stream().anyMatch(l -> l.getMessage().contains("Ignoring entire review-accounts")));
    }

    @Test
    void similarAddressesDoNotGetTheReviewCredential() {
        for (String address : List.of("appreview+test@example.org", "appreview@sub.example.org", "xappreview@example.org")) {
            authNotes.clear();
            submit("email", address);
        }
        assertEquals(3, emails.size());
    }

    @Test
    void wrongGuessesAreAttributedToTheUserAndCappedAcrossSessionsAndBack() {
        submit("email", EMAIL);
        String expiry = codeState().get("expiresAt");
        for (int i = 0; i < TesseraOtpAuthenticator.MAX_ATTEMPTS; i++) {
            submit("otp-code", "wrong");
            assertSame(user, failureUser);
            assertEquals(AuthenticationFlowError.INVALID_CREDENTIALS, failure);
        }
        store.keySet().removeIf(k -> k.startsWith("tessera-otp:throttle:"));
        assertEquals(expiry, codeState().get("expiresAt"));
        submit("reset-email", "true");
        assertNull(attached);
        submit("email", EMAIL);
        // Simulate another auth session with a fresh pending-email note.
        authNotes.clear();
        attached = null;
        submit("email", EMAIL);
        submit("otp-code", CODE);
        assertFalse(success);
        assertEquals("tesseraOtpTooManyAttempts", errorMessage);
        assertEquals("5", codeState().get("attempts"));
        assertTrue(emails.isEmpty());
    }

    @Test
    void expiredReviewChallengeRequiresEmailResubmission() {
        submit("email", EMAIL);
        codeState().put("expiresAt", "1");
        submit("otp-code", CODE);
        assertFalse(success);
        assertEquals(AuthenticationFlowError.EXPIRED_CODE, failure);
        submit("reset-email", "true");
        submit("email", EMAIL);
        submit("otp-code", CODE);
        assertTrue(success);
    }

    @Test
    void removingConfigInvalidatesPendingReviewChallenge() {
        submit("email", EMAIL);
        config.getConfig().clear();
        submit("otp-code", CODE);
        assertFalse(success);
        assertEquals(AuthenticationFlowError.EXPIRED_CODE, failure);
        assertTrue(emails.isEmpty());
    }

    @Test
    void rotationUsesCurrentSecretEvenForAnOutstandingChallenge() {
        submit("email", EMAIL);
        config.getConfig().put(ReviewAccounts.ACCOUNTS, EMAIL + "=87654321");
        submit("otp-code", CODE);
        assertFalse(success);
        submit("otp-code", "87654321");
        assertTrue(success);
    }

    @Test
    void allowlistIsRecheckedAtVerificationAndNeverAffectsOrdinaryUsers() {
        config.getConfig().put(ReviewAccounts.CLIENTS, "civitas-mobile");
        submit("email", EMAIL);
        config.getConfig().put(ReviewAccounts.CLIENTS, "some-other-client");
        submit("otp-code", CODE);
        assertFalse(success);
        assertEquals(AuthenticationFlowError.INVALID_CREDENTIALS, failure);
        assertTrue(emails.isEmpty());
        authNotes.clear();
        submit("email", "ordinary@example.org");
        assertEquals(1, emails.size());
    }

    @Test
    void disallowedClientCannotStartReviewLoginOrSendEmail() {
        config.getConfig().put(ReviewAccounts.CLIENTS, "civitas-mobile");
        clientId = "account-console";
        submit("email", EMAIL);
        assertEquals(AuthenticationFlowError.INVALID_CREDENTIALS, failure);
        assertTrue(store.isEmpty());
        assertTrue(emails.isEmpty());
    }

    @Test
    void requiresExistingEnabledUserWithTheExactEmail() {
        exists = false;
        submit("email", EMAIL);
        assertEquals(AuthenticationFlowError.INVALID_USER, failure);
        exists = true;
        userEmail = "another@example.org";
        submit("email", EMAIL);
        assertEquals(AuthenticationFlowError.INVALID_USER, failure);
        userEmail = EMAIL;
        enabled = false;
        submit("email", EMAIL);
        assertEquals(AuthenticationFlowError.USER_DISABLED, failure);
        assertTrue(emails.isEmpty());
        assertTrue(store.isEmpty());
    }

    @Test
    void bothKeycloakLockoutTypesBlockReviewLogin() {
        temporaryLock = true;
        submit("email", EMAIL);
        assertEquals(AuthenticationFlowError.USER_TEMPORARILY_DISABLED, failure);
        assertTrue(store.isEmpty());
        temporaryLock = false;
        permanentLock = true;
        submit("email", EMAIL);
        assertEquals(AuthenticationFlowError.USER_TEMPORARILY_DISABLED, failure);
        assertTrue(store.isEmpty());
        assertFalse(success);
    }

    @Test
    void lockoutAndDisabledStatusAreRecheckedAtVerification() {
        submit("email", EMAIL);
        temporaryLock = true;
        submit("otp-code", CODE);
        assertFalse(success);
        assertEquals(AuthenticationFlowError.USER_TEMPORARILY_DISABLED, failure);
        temporaryLock = false;
        enabled = false;
        submit("otp-code", CODE);
        assertFalse(success);
        assertEquals(AuthenticationFlowError.USER_DISABLED, failure);
    }

    @Test
    void altchaStillGatesReviewEmailSubmissionWhenEnabled() {
        config.getConfig().put("altcha-hmac-key", "captcha-secret");
        // Missing proof is rejected before a review challenge can be stored.
        submit("email", EMAIL);
        assertEquals("altchaRequired", errorMessage);
        assertTrue(store.isEmpty());
        assertTrue(emails.isEmpty());
    }

    private void submit(String field, String value) {
        fields = new MultivaluedHashMap<>();
        fields.putSingle(field, value);
        authenticator.action(context);
    }

    private Map<String, String> codeState() {
        return store.entrySet().stream().filter(e -> e.getKey().startsWith("tessera-otp:code:"))
                .findFirst().orElseThrow().getValue();
    }

    private static Object unexpectedProvider(Object type) {
        throw new AssertionError("Unexpected provider request: " + type);
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
