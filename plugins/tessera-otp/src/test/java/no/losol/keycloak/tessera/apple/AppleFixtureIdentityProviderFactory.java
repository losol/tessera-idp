// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0
package no.losol.keycloak.tessera.apple;

import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;

/** Test JAR only: production has no endpoint override or fixture registration. */
public final class AppleFixtureIdentityProviderFactory extends
        org.keycloak.broker.provider.AbstractIdentityProviderFactory<TesseraAppleIdentityProvider>
        implements org.keycloak.broker.social.SocialIdentityProviderFactory<TesseraAppleIdentityProvider> {
    @Override public String getId() { return TesseraAppleIdentityProviderFactory.PROVIDER_ID; }
    @Override public String getName() { return "Apple fixture (TEST ONLY)"; }
    @Override public int order() { return 100; }
    @Override public at.klausbetz.provider.AppleIdentityProviderConfig createConfig() {
        return new at.klausbetz.provider.AppleIdentityProviderConfig();
    }
    @Override public TesseraAppleIdentityProvider create(KeycloakSession session, IdentityProviderModel model) {
        TesseraAppleIdentityProvider provider = new TesseraAppleIdentityProvider(session, model) {
            @Override public void prepareClientSecret(String clientId) {
                // Test-only shared broker accepts a synthetic secret; no Apple key.
            }
        };
        provider.getConfig().setAuthorizationUrl("http://127.0.0.1:18089/apple/authorize?response_mode=form_post");
        provider.getConfig().setTokenUrl("http://127.0.0.1:18089/apple/token");
        provider.getConfig().setJwksUrl("http://127.0.0.1:18089/jwks");
        // The fixture verifies client authentication independently. A synthetic JWT
        // avoids any real Apple private key in fixtures or logs.
        provider.getConfig().setClientSecret("synthetic-fixture-secret");
        return provider;
    }
}
