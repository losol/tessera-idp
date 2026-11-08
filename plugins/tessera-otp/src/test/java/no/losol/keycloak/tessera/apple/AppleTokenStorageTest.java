// tessera · Sign in with Apple identity provider for Keycloak
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

package no.losol.keycloak.tessera.apple;

import org.junit.jupiter.api.Test;
import org.keycloak.broker.oidc.OIDCIdentityProviderConfig;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.util.JsonSerialization;

import static org.junit.jupiter.api.Assertions.*;

class AppleTokenStorageTest {

    @Test
    void retainsOnlyTheRefreshToken() throws Exception {
        String stored = AppleIdentityProvider.refreshTokenOnly("""
                {"access_token":"access-secret", "id_token":"id-secret",
                 "refresh_token":"refresh-secret", "expires_in":3600,
                 "extra":"profile-data"}
                """);

        var json = JsonSerialization.mapper.readTree(stored);
        assertEquals(1, json.size());
        assertEquals("refresh-secret", json.get("refresh_token").asText());
    }

    @Test
    void omitsMissingRefreshTokensSoKeycloakCanPreserveThePreviousOne() {
        for (String response : new String[]{"{}", "{\"refresh_token\":null}",
                "{\"refresh_token\":\"  \"}", "{\"access_token\":\"access-secret\"}"}) {
            assertEquals("{}", AppleIdentityProvider.refreshTokenOnly(response));
        }
        assertThrows(IdentityBrokerException.class, () -> AppleIdentityProvider.refreshTokenOnly("invalid-json"));
    }

    @Test
    void deniesBothBrokerTokenEndpointsAndExternalExchange() {
        AppleIdentityProvider provider = new AppleIdentityProvider(null, new OIDCIdentityProviderConfig());

        try (var v1 = provider.retrieveToken(null, null);
             var v2 = provider.retrieveToken(null, null, null, null);
             var exchange = provider.exchangeFromToken(null, null, null, null, null, null)) {
            assertEquals(403, v1.getStatus());
            assertEquals(403, v2.getStatus());
            assertNull(v1.getEntity());
            assertNull(v2.getEntity());
            assertEquals(403, exchange.getStatus());
            assertNull(exchange.getEntity());
        }
        assertFalse(provider.supportsExternalExchange());
    }
}
