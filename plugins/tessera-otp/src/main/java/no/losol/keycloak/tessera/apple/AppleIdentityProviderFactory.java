// tessera · Sign in with Apple identity provider for Keycloak
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

package no.losol.keycloak.tessera.apple;

import org.keycloak.broker.oidc.OIDCIdentityProviderConfig;
import org.keycloak.broker.provider.AbstractIdentityProviderFactory;
import org.keycloak.broker.social.SocialIdentityProviderFactory;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;

import java.util.List;

/**
 * Registers "Apple" among the social identity providers in the admin
 * console. The standard fields hold the Services ID (client id) and the
 * private key Apple issued (client secret); the two extra ones below hold the
 * Team ID and the Key ID that go into the signed client secret. See
 * docs/apple-login.md.
 */
public class AppleIdentityProviderFactory extends AbstractIdentityProviderFactory<AppleIdentityProvider>
        implements SocialIdentityProviderFactory<AppleIdentityProvider> {

    public static final String PROVIDER_ID = "apple";

    @Override
    public String getName() {
        return "Apple";
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public AppleIdentityProvider create(KeycloakSession session, IdentityProviderModel model) {
        return new AppleIdentityProvider(session, new OIDCIdentityProviderConfig(model));
    }

    @Override
    public OIDCIdentityProviderConfig createConfig() {
        OIDCIdentityProviderConfig config = new OIDCIdentityProviderConfig();
        config.setIssuer(AppleIdentityProvider.ISSUER);
        return config;
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return ProviderConfigurationBuilder.create()
                .property()
                    .name(AppleIdentityProvider.CONFIG_TEAM_ID)
                    .label("Team ID")
                    .helpText("The Apple Developer team the Services ID and the signing key belong to. "
                            + "With a Team ID and Key ID set, 'Client secret' must hold the .p8 private key "
                            + "Apple issued (PEM; line breaks may be dropped) and Keycloak signs the Apple "
                            + "client secret from it on every token request. Leave both blank to send "
                            + "'Client secret' as-is, as a ready-made Apple client secret JWT.")
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .add()
                .property()
                    .name(AppleIdentityProvider.CONFIG_KEY_ID)
                    .label("Key ID")
                    .helpText("Key ID of the 'Sign in with Apple' private key in 'Client secret'.")
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .add()
                .build();
    }
}
