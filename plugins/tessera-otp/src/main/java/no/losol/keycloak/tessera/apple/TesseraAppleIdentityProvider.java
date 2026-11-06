// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0
package no.losol.keycloak.tessera.apple;

import at.klausbetz.provider.AppleIdentityProvider;
import at.klausbetz.provider.AppleIdentityProviderConfig;
import jakarta.ws.rs.core.MultivaluedMap;
import java.io.IOException;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.IdentityProviderSyncMode;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.representations.IDToken;
import org.keycloak.representations.JsonWebToken;
import org.keycloak.events.EventBuilder;

/** Web-only Apple broker. The upstream implementation is not registered directly. */
public class TesseraAppleIdentityProvider extends AppleIdentityProvider {
    public static final String ISSUER = "https://appleid.apple.com";

    public TesseraAppleIdentityProvider(KeycloakSession session, IdentityProviderModel model) {
        super(session, safeConfig(model));
    }

    private static AppleIdentityProviderConfig safeConfig(IdentityProviderModel model) {
        // Upstream generates a client secret in the configuration. Keep that mutation
        // request-local rather than replacing the persisted signing key.
        AppleIdentityProviderConfig config = new AppleIdentityProviderConfig(new IdentityProviderModel(model));
        config.setDisableNonce(false);
        config.setTrustEmail(false);
        config.setSyncMode(IdentityProviderSyncMode.IMPORT);
        config.setTokenExchangeAccountLinkingEnabled(false);
        config.setStoreToken(false);
        config.setStoreTokenInSession(false);
        return config;
    }

    @Override
    public JsonWebToken validateToken(String encodedToken) {
        // Upstream 1.17.0 ignores the audience. Use Keycloak's complete OIDC
        // validation, including signature, issuer, activity, audience and azp.
        JsonWebToken token = super.validateToken(encodedToken, false);
        if (token.getSubject() == null || token.getSubject().isBlank()
                || token.getExp() == null || token.getExp() <= 0) {
            throw new IdentityBrokerException("Apple ID token requires subject and expiration");
        }
        return token;
    }

    @Override
    public BrokeredIdentityContext sendTokenRequest(String code, String clientId, String userData,
                                                    AuthenticationSessionModel authSession,
                                                    String redirectUri) throws IOException {
        // Upstream logs the complete error body. Never log token endpoint bodies.
        try (var response = generateTokenRequest(code, clientId, redirectUri).asResponse()) {
            if (response.getStatus() != 200) {
                throw new IdentityBrokerException("Apple token endpoint rejected the request");
            }
            BrokeredIdentityContext identity = getFederatedIdentity(userData, response.asString());
            identity.setIdp(this);
            identity.setAuthenticationSession(authSession);
            return identity;
        }
    }

    @Override
    protected boolean supportsExternalExchange() {
        return false;
    }

    @Override
    protected BrokeredIdentityContext exchangeExternalTokenV1Impl(EventBuilder event,
                                                                 MultivaluedMap<String, String> params) {
        throw new IdentityBrokerException("Apple legacy token exchange is not supported");
    }

    @Override
    protected BrokeredIdentityContext exchangeExternalImpl(EventBuilder event,
                                                                 MultivaluedMap<String, String> params) {
        // Legacy exchange bypasses the web first/post login flows. Account-console
        // authenticated linking uses the normal browser callback and remains supported.
        throw new IdentityBrokerException("Apple legacy token exchange is not supported");
    }

    @Override
    protected void setEmailVerified(UserModel user, BrokeredIdentityContext context) {
        // Never let the generic trustEmail fallback verify an absent/false claim.
        Object validated = context.getContextData().get(VALIDATED_ID_TOKEN);
        Object claim = validated instanceof JsonWebToken token
                ? token.getOtherClaims().get(IDToken.EMAIL_VERIFIED) : null;
        user.setEmailVerified(context.getEmail() != null
                && (Boolean.TRUE.equals(claim) || "true".equals(claim)));
    }
}
