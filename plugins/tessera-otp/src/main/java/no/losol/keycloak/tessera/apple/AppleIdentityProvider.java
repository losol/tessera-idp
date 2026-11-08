// tessera · Sign in with Apple identity provider for Keycloak
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

package no.losol.keycloak.tessera.apple;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;
import org.keycloak.OAuth2Constants;
import org.keycloak.broker.oidc.OIDCIdentityProvider;
import org.keycloak.broker.oidc.OIDCIdentityProviderConfig;
import org.keycloak.broker.provider.AuthenticationRequest;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.broker.social.SocialIdentityProvider;
import org.keycloak.events.EventBuilder;
import org.keycloak.http.simple.SimpleHttpRequest;
import org.keycloak.models.ClientModel;
import org.keycloak.models.FederatedIdentityModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.representations.AccessTokenResponse;
import org.keycloak.representations.JsonWebToken;
import org.keycloak.util.JsonSerialization;
import org.keycloak.vault.VaultStringSecret;

import java.io.IOException;
import java.util.Map;

/**
 * Sign in with Apple, on top of Keycloak's own OIDC broker. Apple is a
 * standard OpenID provider with three deviations, and this class covers
 * exactly those; everything else (nonce, signature via JWKS, issuer and
 * audience checks, account linking, mappers) is Keycloak's.
 *
 * <ol>
 *   <li>When the name or email scope is requested, Apple delivers the
 *       authorization response as a cross-site <em>POST</em>
 *       ({@code response_mode=form_post}). Keycloak's broker callback only
 *       has a GET handler, so {@link Endpoint} adds a POST one that hands the
 *       same parameters to it.</li>
 *   <li>The client secret is a JWT signed with the team's ES256 key, see
 *       {@link AppleClientSecret}.</li>
 *   <li>The user's name is not in the ID token. Apple sends it once, on first
 *       consent, as a {@code user} JSON form field next to the code.</li>
 * </ol>
 *
 * The Apple ID token carries {@code email_verified} (as a boolean or the
 * string {@code "true"}); Keycloak's OIDC provider already reads it, so the
 * first-broker-login flow can trust it like any other verified claim. With
 * "Hide my email" the address is a {@code privaterelay.appleid.com} relay,
 * which is a working, verified mailbox and is used as-is.
 */
public class AppleIdentityProvider extends OIDCIdentityProvider implements SocialIdentityProvider<OIDCIdentityProviderConfig> {

    public static final String ISSUER = "https://appleid.apple.com";
    static final String AUTHORIZATION_URL = ISSUER + "/auth/authorize";
    static final String TOKEN_URL = ISSUER + "/auth/token";
    static final String JWKS_URL = ISSUER + "/auth/keys";
    static final String DEFAULT_SCOPE = "name email";

    // Provider config keys, see AppleIdentityProviderFactory. The private key
    // goes in the standard "Client secret" field: Keycloak masks that one in
    // the admin API and console and resolves ${vault.…} references for it.
    static final String CONFIG_TEAM_ID = "teamId";
    static final String CONFIG_KEY_ID = "keyId";

    static final String RESPONSE_MODE = "response_mode";
    static final String FORM_POST = "form_post";
    static final String USER_FORM_FIELD = "user";
    // Apple's own error code when the user dismisses the consent dialog.
    static final String USER_CANCELLED = "user_cancelled_authorize";

    // Request-scoped hand-over of the `user` form field from the POST
    // endpoint to getFederatedIdentity (KeycloakSession lives per request).
    private static final String USER_SESSION_ATTRIBUTE = AppleIdentityProvider.class.getName() + ".user";

    public AppleIdentityProvider(KeycloakSession session, OIDCIdentityProviderConfig config) {
        super(session, config);
        // Pinned: the model only stores what the admin enters (client id,
        // team id, key id, private key). The copy constructor in the factory
        // keeps these request-local.
        config.setAuthorizationUrl(AUTHORIZATION_URL);
        config.setTokenUrl(TOKEN_URL);
        config.setIssuer(ISSUER);
        config.setJwksUrl(JWKS_URL);
        config.setUseJwksUrl(true);
        config.setValidateSignature(true);
        config.setDisableUserInfoService(true); // Apple has no userinfo endpoint
        config.setClientAuthMethod(OIDCLoginProtocol.CLIENT_SECRET_POST);
    }

    @Override
    protected String getDefaultScopes() {
        return DEFAULT_SCOPE;
    }

    @Override
    protected UriBuilder createAuthorizationUrl(AuthenticationRequest request) {
        return super.createAuthorizationUrl(request).queryParam(RESPONSE_MODE, FORM_POST);
    }

    @Override
    public Object callback(RealmModel realm, AuthenticationCallback callback, EventBuilder event) {
        return new Endpoint(callback, realm, event, this);
    }

    /**
     * With a Team ID and Key ID configured, the "Client secret" field holds
     * Apple's private key and the client secret is signed from it here.
     * Without them, the field is taken to hold a ready-made Apple client
     * secret JWT (signed elsewhere, valid up to six months) and is sent as-is,
     * which is Keycloak's default behaviour.
     */
    @Override
    public SimpleHttpRequest authenticateTokenRequest(SimpleHttpRequest tokenRequest) {
        String teamId = getConfig().getConfig().get(CONFIG_TEAM_ID);
        String keyId = getConfig().getConfig().get(CONFIG_KEY_ID);
        if (isBlank(teamId) || isBlank(keyId)) {
            return super.authenticateTokenRequest(tokenRequest);
        }
        try (VaultStringSecret vaulted = session.vault().getStringSecret(getConfig().getClientSecret())) {
            String secret = AppleClientSecret.sign(
                    vaulted.get().orElse(getConfig().getClientSecret()), keyId, teamId, getConfig().getClientId());
            return tokenRequest
                    .param(OAUTH2_PARAMETER_CLIENT_ID, getConfig().getClientId())
                    .param(OAUTH2_PARAMETER_CLIENT_SECRET, secret);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Keycloak treats a token without {@code exp} (or with {@code exp=0}) as
     * never expiring. Apple always sets one, so a token without it is not
     * Apple's; reject it rather than accept it for ever.
     */
    @Override
    protected JsonWebToken validateToken(String encodedToken, boolean ignoreAudience) {
        JsonWebToken token = super.validateToken(encodedToken, ignoreAudience);
        if (token.getExp() == null || token.getExp() <= 0) {
            throw new IdentityBrokerException("Apple ID token has no expiration");
        }
        return token;
    }

    @Override
    public BrokeredIdentityContext getFederatedIdentity(String response) {
        BrokeredIdentityContext identity = super.getFederatedIdentity(response);
        // Keycloak persists this field only with Store tokens enabled. Keep
        // the deletion credential, not the access/ID tokens or profile claims.
        if (Boolean.TRUE.equals(getConfig().isStoreToken())) {
            identity.setToken(refreshTokenOnly(response));
        }
        Object user = session.getAttribute(USER_SESSION_ATTRIBUTE);
        if (user instanceof String json) {
            AppleUser.applyName(identity, json);
        }
        return identity;
    }

    static String refreshTokenOnly(String response) {
        try {
            String refreshToken = JsonSerialization.readValue(response,
                    AccessTokenResponse.class).getRefreshToken();
            // An empty object lets Keycloak's returning-login merge preserve
            // the previous refresh token when Apple omits a replacement.
            return JsonSerialization.writeValueAsString(isBlank(refreshToken)
                    ? Map.of() : Map.of(OAuth2Constants.REFRESH_TOKEN, refreshToken));
        } catch (IOException e) {
            throw new IdentityBrokerException("Could not decode Apple token response");
        }
    }

    /** The stored credential is private to deletion, including for read-token holders. */
    @Override
    public Response retrieveToken(KeycloakSession session, FederatedIdentityModel identity) {
        return Response.status(Response.Status.FORBIDDEN).build();
    }

    @Override
    public Response retrieveToken(KeycloakSession session, FederatedIdentityModel identity,
                                  UserSessionModel userSession, UserModel user) {
        return Response.status(Response.Status.FORBIDDEN).build();
    }

    @Override
    public Response exchangeFromToken(UriInfo uriInfo, EventBuilder event, ClientModel authorizedClient,
                                      UserSessionModel userSession, UserModel user,
                                      MultivaluedMap<String, String> params) {
        return Response.status(Response.Status.FORBIDDEN).build();
    }

    @Override
    protected boolean supportsExternalExchange() {
        return false;
    }

    /**
     * Keycloak's callback endpoint plus a POST handler for Apple's
     * {@code form_post} response. The parameters are the same as in the GET
     * case; they are just read from the form body and passed on to the
     * inherited handler, which does the state check, the code exchange and
     * the rest. Keycloak's auth-session cookies are SameSite=None, so the
     * browser still sends them on this cross-site POST.
     */
    protected static class Endpoint extends OIDCEndpoint {

        private final AppleIdentityProvider provider;

        public Endpoint(AuthenticationCallback callback, RealmModel realm, EventBuilder event, AppleIdentityProvider provider) {
            super(callback, realm, event, provider);
            this.provider = provider;
        }

        @POST
        @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
        public Response formPostResponse(@FormParam(OAUTH2_PARAMETER_STATE) String state,
                                         @FormParam(OAUTH2_PARAMETER_CODE) String code,
                                         @FormParam(OAuth2Constants.ERROR) String error,
                                         @FormParam(USER_FORM_FIELD) String user) {
            if (user != null) {
                provider.session.setAttribute(USER_SESSION_ATTRIBUTE, user);
            }
            // Apple's cancel code is not the OAuth one; map it so Keycloak
            // takes the user back to the login page instead of an error page.
            return authResponse(state, code, USER_CANCELLED.equals(error) ? ACCESS_DENIED : error, null);
        }
    }
}
