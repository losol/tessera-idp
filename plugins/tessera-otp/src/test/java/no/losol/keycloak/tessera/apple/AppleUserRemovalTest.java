// tessera · Sign in with Apple identity provider for Keycloak
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

package no.losol.keycloak.tessera.apple;

import com.sun.net.httpserver.HttpServer;
import org.apache.http.HttpHost;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.broker.oidc.OIDCIdentityProviderConfig;
import org.keycloak.connections.httpclient.HttpClientProvider;
import org.keycloak.jose.jws.JWSInput;
import org.keycloak.models.*;
import org.keycloak.provider.ProviderEventListener;
import org.keycloak.representations.JsonWebToken;
import org.keycloak.services.DefaultKeycloakTransactionManager;
import org.keycloak.tracing.NoopTracingProvider;
import org.keycloak.tracing.TracingProvider;
import org.keycloak.vault.VaultStringSecret;
import org.keycloak.vault.VaultTranscriber;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class AppleUserRemovalTest {

    private HttpServer server;
    private CloseableHttpClient http;
    private final List<Map<String, String>> requests = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> contentTypes = new CopyOnWriteArrayList<>();
    private final Map<String, Object> attributes = new HashMap<>();
    private int status = 200;
    private OIDCIdentityProviderConfig config;
    private FederatedIdentityModel link;
    private KeycloakSession session;
    private DefaultKeycloakTransactionManager transaction;
    private ProviderEventListener listener;
    private RealmModel realm;
    private UserModel user;
    private String vaultValue;
    private boolean deleted;
    private final List<LogRecord> warnings = new ArrayList<>();
    private final Logger logger = Logger.getLogger(AppleUserRemovalListener.class.getName());
    private final Handler logCapture = new Handler() {
        public void publish(LogRecord record) { warnings.add(record); }
        public void flush() {}
        public void close() {}
    };

    @BeforeEach
    void setUp() throws Exception {
        logger.addHandler(logCapture);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth/revoke", exchange -> {
            paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            contentTypes.add(exchange.getRequestHeaders().getFirst("Content-Type"));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> form = new HashMap<>();
            for (String part : body.split("&")) {
                String[] pair = part.split("=", 2);
                form.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            requests.add(form);
            // An error body deliberately echoes credentials. It must not be logged.
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Location", "/redirect-must-not-be-followed");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        // Route only in the test: production still posts to the pinned HTTPS Apple URL.
        http = HttpClients.custom().setRoutePlanner((target, request, context) -> {
            assertEquals("appleid.apple.com", target.getHostName());
            assertEquals("https", target.getSchemeName());
            return new HttpRoute(new HttpHost("127.0.0.1", server.getAddress().getPort()));
        }).build();

        config = new OIDCIdentityProviderConfig();
        config.setAlias("apple");
        config.setProviderId("apple");
        config.setStoreToken(true);
        config.setClientId("no.losol.tessera.web");
        config.setClientSecret("pre-made-client-secret");
        link = new FederatedIdentityModel("apple", "apple-subject", "name",
                "{\"refresh_token\":\"refresh+secret&=token\"}");
        realm = proxy(RealmModel.class, (p, m, a) -> {
            if (m.getName().equals("getId")) return "realm-id";
            throw new AssertionError(m);
        });
        user = proxy(UserModel.class, (p, m, a) -> {
            if (m.getName().equals("getId")) return "user-id";
            throw new AssertionError(m);
        });
        UserProvider users = proxy(UserProvider.class, (p, m, a) -> {
            assertFalse(deleted, "No model reads after removal");
            if (m.getName().equals("getFederatedIdentity")) return link;
            throw new AssertionError(m);
        });
        IdentityProviderStorageProvider providers = proxy(IdentityProviderStorageProvider.class, (p, m, a) -> {
            assertFalse(deleted, "No provider reads after removal");
            if (m.getName().equals("getByAlias")) return config;
            throw new AssertionError(m);
        });
        HttpClientProvider client = proxy(HttpClientProvider.class, (p, m, a) -> switch (m.getName()) {
            case "getHttpClient" -> http;
            case "getMaxConsumedResponseSize" -> 10000L;
            default -> throw new AssertionError(m);
        });
        VaultTranscriber vault = proxy(VaultTranscriber.class, (p, m, a) -> {
            if (m.getName().equals("getStringSecret")) return new VaultStringSecret() {
                public Optional<String> get() { return Optional.ofNullable(vaultValue); }
                public void close() {}
            };
            throw new AssertionError(m);
        });
        session = proxy(KeycloakSession.class, (p, m, a) -> switch (m.getName()) {
            case "users" -> users;
            case "identityProviders" -> providers;
            case "getTransactionManager" -> transaction;
            case "getAttribute" -> attributes.get(a[0]);
            case "setAttribute" -> attributes.put((String) a[0], a[1]);
            case "removeAttribute" -> attributes.remove(a[0]);
            case "vault" -> vault;
            case "getProvider" -> a[0] == HttpClientProvider.class ? client
                    : a[0] == TracingProvider.class ? new NoopTracingProvider() : null;
            default -> throw new AssertionError(m);
        });
        transaction = new DefaultKeycloakTransactionManager(session);
        transaction.setJTAPolicy(KeycloakTransactionManager.JTAPolicy.NOT_SUPPORTED);
        transaction.begin();
        // Exercise registration through the existing service-loaded Apple factory.
        AtomicReference<ProviderEventListener> registered = new AtomicReference<>();
        new AppleIdentityProviderFactory().postInit(proxy(KeycloakSessionFactory.class, (p, m, a) -> {
            if (m.getName().equals("register")) { registered.set((ProviderEventListener) a[0]); return null; }
            throw new AssertionError(m);
        }));
        listener = registered.get();
        assertNotNull(listener);
    }

    @AfterEach
    void tearDown() throws Exception {
        logger.removeHandler(logCapture);
        if (http != null) http.close();
        if (server != null) server.stop(0);
    }

    @Test
    void revokesOnceAfterCommittedRemovalWithASignedVaultSecret() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair key = generator.generateKeyPair();
        vaultValue = Base64.getEncoder().encodeToString(key.getPrivate().getEncoded());
        config.setClientSecret("${vault.apple-key}");
        config.getConfig().put("teamId", "TEAM567890");
        config.getConfig().put("keyId", "ABC123DEFG");

        beforeRemoval();
        assertTrue(requests.isEmpty());
        afterRemoval();
        afterRemoval(); // duplicate notifications cannot enqueue a second call
        assertTrue(requests.isEmpty());
        transaction.commit();

        assertEquals(List.of("POST /auth/revoke"), paths);
        assertTrue(contentTypes.get(0).startsWith("application/x-www-form-urlencoded"));
        Map<String, String> form = requests.get(0);
        assertEquals(Set.of("client_id", "client_secret", "token", "token_type_hint"), form.keySet());
        assertEquals("no.losol.tessera.web", form.get("client_id"));
        assertEquals("refresh+secret&=token", form.get("token"));
        assertEquals("refresh_token", form.get("token_type_hint"));
        JWSInput jwt = new JWSInput(form.get("client_secret"));
        assertEquals("ES256", jwt.getHeader().getAlgorithm().name());
        assertEquals("ABC123DEFG", jwt.getHeader().getKeyId());
        JsonWebToken claims = jwt.readJsonContent(JsonWebToken.class);
        assertEquals("TEAM567890", claims.getIssuer());
        assertEquals(config.getClientId(), claims.getSubject());
        assertArrayEquals(new String[]{AppleIdentityProvider.ISSUER}, claims.getAudience());
        assertTrue(claims.isActive());
        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(key.getPublic());
        verifier.update(jwt.getEncodedSignatureInput().getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(jwt.getSignature()));
        assertTrue(deleted);
    }

    @Test
    void acceptsAPreMadeSecret() {
        beforeRemoval();
        afterRemoval();
        transaction.commit();
        assertEquals("pre-made-client-secret", requests.get(0).get("client_secret"));
    }

    @Test
    void skipsRollback() {
        beforeRemoval();
        afterRemoval();
        transaction.rollback();
        assertTrue(requests.isEmpty());
    }

    @Test
    void skipsFailedRemovalEvenIfTransactionCommits() {
        beforeRemoval();
        transaction.commit();
        assertTrue(requests.isEmpty());
    }

    @Test
    void skipsFailedDatabaseCommit() {
        beforeRemoval();
        afterRemoval();
        transaction.enlist(new AbstractKeycloakTransaction() {
            protected void commitImpl() { throw new IllegalStateException("database commit failed"); }
            protected void rollbackImpl() {}
        });
        assertThrows(IllegalStateException.class, transaction::commit);
        assertTrue(requests.isEmpty());
    }

    @Test
    void appleFailureDoesNotFailDeletion() {
        status = 500;
        beforeRemoval();
        afterRemoval();
        assertDoesNotThrow(transaction::commit);
        assertEquals(1, requests.size());
        assertTrue(deleted);
        assertEquals(1, warnings.size());
        String warning = warnings.get(0).getMessage();
        assertTrue(warning.contains("500"));
        assertFalse(warning.contains("refresh+secret"));
        assertFalse(warning.contains("pre-made-client-secret"));
        assertNull(warnings.get(0).getThrown());
    }

    @Test
    void doesNotFollowRedirects() {
        status = 307;
        beforeRemoval();
        afterRemoval();
        assertDoesNotThrow(transaction::commit);
        assertEquals(1, requests.size());
    }

    @Test
    void networkFailureDoesNotFailDeletion() {
        beforeRemoval();
        afterRemoval();
        server.stop(0);
        assertDoesNotThrow(transaction::commit);
        assertTrue(deleted);
    }

    @Test
    void signingFailureDoesNotFailDeletion() {
        config.getConfig().put("teamId", "team");
        config.getConfig().put("keyId", "key");
        beforeRemoval(); // configured secret is not a private key
        afterRemoval();
        assertDoesNotThrow(transaction::commit);
        assertTrue(requests.isEmpty());
    }

    @Test
    void skipsUnlinkedUsersAndRealmsWithoutApple() {
        link = null;
        beforeRemoval();
        config = null;
        beforeRemoval();
        afterRemoval();
        transaction.commit();
        assertTrue(requests.isEmpty());
    }

    @Test
    void skipsLegacyUsersWithoutTokensAndInvalidStoredResponses() {
        for (String token : Arrays.asList(null, "{}", "null", "invalid-json", "{\"id_token\":\"id-only\"}")) {
            link.setToken(token);
            beforeRemoval();
        }
        afterRemoval();
        transaction.commit();
        assertTrue(requests.isEmpty());
    }

    private void beforeRemoval() {
        listener.onEvent(new UserModel.UserPreRemovedEvent() {
            public RealmModel getRealm() { return realm; }
            public UserModel getUser() { return user; }
            public KeycloakSession getKeycloakSession() { return session; }
        });
    }

    private void afterRemoval() {
        deleted = true;
        link = null;
        listener.onEvent(new UserModel.UserRemovedEvent() {
            public RealmModel getRealm() { return realm; }
            public UserModel getUser() { return user; }
            public KeycloakSession getKeycloakSession() { return session; }
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
