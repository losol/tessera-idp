// tessera · Sign in with Apple identity provider for Keycloak
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

package no.losol.keycloak.tessera.apple;

import org.apache.http.client.config.RequestConfig;
import org.jboss.logging.Logger;
import org.keycloak.OAuth2Constants;
import org.keycloak.broker.oidc.OIDCIdentityProviderConfig;
import org.keycloak.http.simple.SimpleHttp;
import org.keycloak.http.simple.SimpleHttpRequest;
import org.keycloak.models.AbstractKeycloakTransaction;
import org.keycloak.models.FederatedIdentityModel;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.provider.ProviderEvent;
import org.keycloak.provider.ProviderEventListener;
import org.keycloak.representations.AccessTokenResponse;
import org.keycloak.util.JsonSerialization;

/** Captures the link before cleanup; revokes only after successful removal and commit. */
final class AppleUserRemovalListener implements ProviderEventListener {

    static final String REVOKE_URL = AppleIdentityProvider.ISSUER + "/auth/revoke";
    private static final Logger LOG = Logger.getLogger(AppleUserRemovalListener.class);
    private static final String PENDING = AppleUserRemovalListener.class.getName() + ".pending.";

    @Override
    public void onEvent(ProviderEvent event) {
        try {
            if (event instanceof UserModel.UserPreRemovedEvent before) {
                capture(before.getKeycloakSession(), before.getRealm(), before.getUser());
            } else if (event instanceof UserModel.UserRemovedEvent removed) {
                KeycloakSession session = removed.getKeycloakSession();
                String key = key(removed.getRealm(), removed.getUser());
                Object pending = session.getAttribute(key);
                session.removeAttribute(key);
                if (pending instanceof SimpleHttpRequest request) {
                    session.getTransactionManager().enlistAfterCompletion(new AbstractKeycloakTransaction() {
                        @Override
                        protected void commitImpl() {
                            revoke(request);
                        }

                        @Override
                        protected void rollbackImpl() {
                            // The account still exists; keep its Apple authorization.
                        }
                    });
                }
            }
        } catch (Exception e) {
            // Exception messages/causes and Apple bodies may contain credentials.
            LOG.warn("Apple token revocation could not be prepared; account deletion continues");
        }
    }

    private void capture(KeycloakSession session, RealmModel realm, UserModel user) throws Exception {
        IdentityProviderModel model = session.identityProviders().getByAlias("apple");
        if (model == null || !AppleIdentityProviderFactory.PROVIDER_ID.equals(model.getProviderId())) {
            return;
        }
        FederatedIdentityModel link = session.users().getFederatedIdentity(realm, user, model.getAlias());
        if (link == null) {
            return;
        }
        String token = link.getToken() == null ? null
                : JsonSerialization.readValue(link.getToken(), AccessTokenResponse.class).getRefreshToken();
        if (token == null || token.isBlank()) {
            LOG.warn("Apple token revocation skipped: no stored refresh token; account deletion continues");
            return;
        }

        // Resolve vault secrets and construct the request while the original
        // session/realm are available. The callback performs no model reads.
        AppleIdentityProvider provider = new AppleIdentityProvider(session, new OIDCIdentityProviderConfig(model));
        SimpleHttpRequest request = provider.authenticateTokenRequest(SimpleHttp.create(session)
                .withRequestConfig(RequestConfig.custom()
                        .setConnectTimeout(5000).setSocketTimeout(5000).setConnectionRequestTimeout(5000)
                        .setRedirectsEnabled(false).build())
                .doPost(REVOKE_URL)
                .param(OAuth2Constants.TOKEN, token)
                .param("token_type_hint", OAuth2Constants.REFRESH_TOKEN));
        session.setAttribute(key(realm, user), request);
    }

    private static String key(RealmModel realm, UserModel user) {
        return PENDING + realm.getId() + "." + user.getId();
    }

    private static void revoke(SimpleHttpRequest request) {
        try {
            int status = request.asStatus();
            if (status != 200) {
                LOG.warnf("Apple token revocation failed (HTTP %d); account deletion committed", status);
            }
        } catch (Exception e) {
            LOG.warn("Apple token revocation request failed; account deletion committed");
        }
    }
}
