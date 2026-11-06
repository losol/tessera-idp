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
            @Override public org.keycloak.broker.provider.BrokeredIdentityContext sendTokenRequest(
                    String code, String clientId, String profile,
                    org.keycloak.sessions.AuthenticationSessionModel authenticationSession,
                    String redirectUri) throws java.io.IOException {
                if ("true".equals(model.getConfig().get("fixtureRequireCrossSite"))
                        && !"cross-site".equals(session.getContext().getRequestHeaders()
                                .getHeaderString("Sec-Fetch-Site"))) {
                    throw new org.keycloak.broker.provider.IdentityBrokerException(
                            "Fixture requires a genuinely cross-site browser POST");
                }
                return super.sendTokenRequest(code, clientId, profile, authenticationSession, redirectUri);
            }
        };
        String base = System.getProperty("tessera.fixture.base-url", "http://127.0.0.1:18089");
        String scenario = model.getConfig().get("fixtureScenario");
        String automatic = scenario == null ? "" : "&fixture_scenario="
                + java.net.URLEncoder.encode(scenario, java.nio.charset.StandardCharsets.UTF_8);
        provider.getConfig().setAuthorizationUrl(base + "/apple/authorize?response_mode=form_post" + automatic);
        provider.getConfig().setTokenUrl(base + "/apple/token");
        provider.getConfig().setJwksUrl(base + "/jwks");
        // The fixture verifies client authentication independently. A synthetic JWT
        // avoids any real Apple private key in fixtures or logs.
        provider.getConfig().setClientSecret("synthetic-fixture-secret");
        return provider;
    }
}
