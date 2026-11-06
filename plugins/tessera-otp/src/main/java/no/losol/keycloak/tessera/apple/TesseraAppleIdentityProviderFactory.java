// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0
package no.losol.keycloak.tessera.apple;

import at.klausbetz.provider.AppleIdentityProviderConfig;
import java.util.List;
import org.keycloak.broker.provider.AbstractIdentityProviderFactory;
import org.keycloak.broker.social.SocialIdentityProviderFactory;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;

public final class TesseraAppleIdentityProviderFactory
        extends AbstractIdentityProviderFactory<TesseraAppleIdentityProvider>
        implements SocialIdentityProviderFactory<TesseraAppleIdentityProvider> {
    public static final String PROVIDER_ID = "tessera-apple";

    @Override public String getName() { return "Apple (Tessera)"; }
    @Override public String getId() { return PROVIDER_ID; }
    @Override public AppleIdentityProviderConfig createConfig() { return new AppleIdentityProviderConfig(); }
    @Override public TesseraAppleIdentityProvider create(KeycloakSession session, IdentityProviderModel model) {
        return new TesseraAppleIdentityProvider(session, model);
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return ProviderConfigurationBuilder.create()
                .property().name("teamId").label("Apple Team ID")
                .helpText("Apple Developer Team ID. Signing key stays in this IdP.")
                .type(ProviderConfigProperty.STRING_TYPE).add()
                .property().name("keyId").label("Apple Key ID")
                .helpText("Identifier for the Sign in with Apple private signing key.")
                .type(ProviderConfigProperty.STRING_TYPE).add()
                .build();
    }
}
