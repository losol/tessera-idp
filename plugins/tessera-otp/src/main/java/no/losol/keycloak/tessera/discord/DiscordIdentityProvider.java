// tessera · Discord identity provider for Keycloak
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

package no.losol.keycloak.tessera.discord;

import com.fasterxml.jackson.databind.JsonNode;
import org.jboss.logging.Logger;
import org.keycloak.broker.oidc.AbstractOAuth2IdentityProvider;
import org.keycloak.broker.oidc.OAuth2IdentityProviderConfig;
import org.keycloak.broker.oidc.mappers.AbstractJsonUserAttributeMapper;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.broker.social.SocialIdentityProvider;
import org.keycloak.events.EventBuilder;
import org.keycloak.http.simple.SimpleHttp;
import org.keycloak.http.simple.SimpleHttpResponse;
import org.keycloak.models.KeycloakSession;

import java.io.IOException;

/**
 * Login with Discord, on Keycloak's generic OAuth 2.0 broker. Discord is not
 * an OpenID provider (no ID token, no discovery), so after the code exchange
 * the user is read from {@code /users/@me} with the access token, the way
 * Keycloak's own GitHub provider does it.
 *
 * What the realm gets from the profile is decided in {@link DiscordUser}; the
 * one point that matters for account linking is that the email is marked
 * verified only when Discord says so, because the first-broker-login flow
 * links to an existing account by email on that basis (see
 * docs/discord-login.md). The endpoints are pinned here; the model only
 * stores what the admin enters.
 */
public class DiscordIdentityProvider extends AbstractOAuth2IdentityProvider<OAuth2IdentityProviderConfig>
        implements SocialIdentityProvider<OAuth2IdentityProviderConfig> {

    private static final Logger LOG = Logger.getLogger(DiscordIdentityProvider.class);

    public static final String AUTHORIZATION_URL = "https://discord.com/oauth2/authorize";
    public static final String TOKEN_URL = "https://discord.com/api/oauth2/token";
    public static final String PROFILE_URL = "https://discord.com/api/users/@me";
    // `identify` is the id and username; `email` adds the address and whether
    // Discord has verified it. Nothing about guilds or connections.
    public static final String DEFAULT_SCOPE = "identify email";

    public DiscordIdentityProvider(KeycloakSession session, OAuth2IdentityProviderConfig config) {
        super(session, config);
        config.setAuthorizationUrl(AUTHORIZATION_URL);
        config.setTokenUrl(TOKEN_URL);
        config.setUserInfoUrl(PROFILE_URL);
    }

    @Override
    protected String getDefaultScopes() {
        return DEFAULT_SCOPE;
    }

    /** No token exchange from an external Discord token: nothing needs it, and it is one less way in. */
    @Override
    protected boolean supportsExternalExchange() {
        return false;
    }

    @Override
    protected String getProfileEndpointForValidation(EventBuilder event) {
        return PROFILE_URL;
    }

    @Override
    protected BrokeredIdentityContext doGetFederatedIdentity(String accessToken) {
        try (SimpleHttpResponse response = SimpleHttp.create(session).doGet(PROFILE_URL)
                .header("Authorization", "Bearer " + accessToken)
                .acceptJson()
                .asResponse()) {
            if (response.getStatus() != 200) {
                // The body can carry the token on some errors; the status is enough.
                LOG.warnf("Discord profile endpoint answered %d", response.getStatus());
                throw new IdentityBrokerException("Could not read the Discord profile");
            }
            return extractIdentityFromProfile(null, response.asJson());
        } catch (IOException e) {
            throw new IdentityBrokerException("Could not read the Discord profile", e);
        }
    }

    @Override
    protected BrokeredIdentityContext extractIdentityFromProfile(EventBuilder event, JsonNode profile) {
        BrokeredIdentityContext identity = new BrokeredIdentityContext(DiscordUser.id(profile), getConfig());
        DiscordUser.apply(identity, profile);
        identity.setIdp(this);
        // Lets attribute mappers pick further fields (avatar, locale) off the profile.
        AbstractJsonUserAttributeMapper.storeUserProfileForMapper(identity, profile, getConfig().getAlias());
        return identity;
    }
}
