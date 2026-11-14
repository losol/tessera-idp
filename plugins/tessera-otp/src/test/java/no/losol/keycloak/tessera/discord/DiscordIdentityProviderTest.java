// tessera · Discord identity provider for Keycloak
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

package no.losol.keycloak.tessera.discord;

import com.sun.net.httpserver.HttpServer;
import org.apache.http.HttpHost;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.broker.oidc.OAuth2IdentityProviderConfig;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.connections.httpclient.HttpClientProvider;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the real provider's code-exchange follow-up against a local stand-in
 * for Discord's API, routed there only in the test: production keeps the
 * pinned HTTPS URLs.
 */
class DiscordIdentityProviderTest {

    private HttpServer server;
    private CloseableHttpClient http;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private int status = 200;
    private String profile = "{\"id\":\"80351110224678912\",\"username\":\"nelly\",\"global_name\":\"Nelly\","
            + "\"email\":\"nelly@example.org\",\"verified\":true}";
    private DiscordIdentityProvider provider;
    private OAuth2IdentityProviderConfig config;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = profile.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        http = HttpClients.custom().setRoutePlanner((target, request, context) -> {
            assertEquals("discord.com", target.getHostName());
            assertEquals("https", target.getSchemeName());
            return new HttpRoute(new HttpHost("127.0.0.1", server.getAddress().getPort()));
        }).build();

        HttpClientProvider client = proxy(HttpClientProvider.class, (p, m, a) -> switch (m.getName()) {
            case "getHttpClient" -> http;
            case "getMaxConsumedResponseSize" -> 10000L;
            default -> throw new AssertionError(m);
        });
        KeycloakSession session = proxy(KeycloakSession.class, (p, m, a) -> {
            if (m.getName().equals("getProvider") && a[0] == HttpClientProvider.class) return client;
            throw new AssertionError(m);
        });

        IdentityProviderModel model = new IdentityProviderModel();
        model.setAlias("discord");
        model.setProviderId("discord");
        model.setEnabled(true);
        config = new OAuth2IdentityProviderConfig(model);
        config.setClientId("123456789012345678");
        config.setClientSecret("client-secret");
        provider = new DiscordIdentityProvider(session, config);
    }

    @AfterEach
    void tearDown() throws Exception {
        http.close();
        server.stop(0);
    }

    @Test
    void pinsDiscordsEndpointsAndScopes() {
        assertEquals("https://discord.com/oauth2/authorize", config.getAuthorizationUrl());
        assertEquals("https://discord.com/api/oauth2/token", config.getTokenUrl());
        assertEquals("https://discord.com/api/users/@me", config.getUserInfoUrl());
        assertEquals("identify email", provider.getDefaultScopes());
        assertEquals("discord", new DiscordIdentityProviderFactory().getId());
    }

    @Test
    void readsTheProfileWithTheAccessToken() {
        BrokeredIdentityContext identity = provider.getFederatedIdentity(
                "{\"access_token\":\"acc-token\",\"token_type\":\"Bearer\",\"expires_in\":604800,\"scope\":\"identify email\"}");

        assertEquals(List.of("GET /api/users/@me"), requests);
        assertEquals(List.of("Bearer acc-token"), authorizations);
        assertEquals("80351110224678912", identity.getId());
        assertEquals("nelly", identity.getUsername());
        assertEquals("Nelly", identity.getFirstName());
        assertEquals("nelly@example.org", identity.getEmail());
        assertTrue(identity.isEmailVerified());
        assertSame(provider, identity.getIdp());
    }

    @Test
    void aFailedProfileCallFailsTheLogin() {
        status = 401;
        profile = "{\"message\":\"401: Unauthorized\",\"code\":0}";
        assertThrows(IdentityBrokerException.class, () -> provider.getFederatedIdentity("{\"access_token\":\"bad\"}"));
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
