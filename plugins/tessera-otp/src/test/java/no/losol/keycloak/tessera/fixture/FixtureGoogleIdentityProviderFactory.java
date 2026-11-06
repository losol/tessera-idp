// SPDX-License-Identifier: MPL-2.0
package no.losol.keycloak.tessera.fixture;

import org.keycloak.models.KeycloakSession;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.social.google.GoogleIdentityProvider;
import org.keycloak.social.google.GoogleIdentityProviderConfig;
import org.keycloak.social.google.GoogleIdentityProviderFactory;

/** Test JAR only. Overrides endpoints while retaining Google's validation implementation. */
public final class FixtureGoogleIdentityProviderFactory extends GoogleIdentityProviderFactory {
    @Override public int order() { return 100; }
    @Override public GoogleIdentityProvider create(KeycloakSession session, IdentityProviderModel model) {
        var config = new GoogleIdentityProviderConfig(model);
        var provider = new GoogleIdentityProvider(session, config);
        config.setAuthorizationUrl("http://127.0.0.1:18089/google/authorize");
        config.setTokenUrl("http://127.0.0.1:18089/google/token");
        config.setJwksUrl("http://127.0.0.1:18089/jwks");
        config.setDisableUserInfoService(true);
        return provider;
    }
}
