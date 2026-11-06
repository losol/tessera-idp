// SPDX-License-Identifier: MPL-2.0
package no.losol.keycloak.tessera.broker;

import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.models.IdentityProviderSyncMode;
import org.keycloak.models.UserModel;

final class BrokerPolicySupport {
    private BrokerPolicySupport() { }

    static void configuration(BrokeredIdentityContext broker) {
        var idp = broker.getIdpConfig();
        String requiredAlias = "google".equals(idp.getProviderId()) ? "google" : "apple";
        if (!requiredAlias.equals(idp.getAlias()) || idp.getSyncMode() != IdentityProviderSyncMode.IMPORT
                || Boolean.TRUE.equals(idp.isTrustEmail())
                || !"true".equals(idp.getConfig().get("validateSignature"))
                || Boolean.parseBoolean(idp.getConfig().get("disableNonce"))) {
            throw new IdentityBrokerException("Tessera requires IMPORT, trustEmail=false, signature and nonce validation");
        }
    }

    static boolean protectedAccount(UserModel user) {
        if (user == null) return false;
        // Conservative: password, passkey, TOTP, recovery credentials and pending
        // required actions must never be replaced with an email-only decision.
        return !user.isEnabled() || user.credentialManager().getStoredCredentialsStream().findAny().isPresent()
                || user.getRequiredActionsStream().findAny().isPresent();
    }

    static boolean strict(AuthenticationFlowContext context, UserModel user) {
        var auth = context.getAuthenticationSession();
        return BrokerLinkingPolicy.strict(context.getRealm().getAttribute(BrokerLinkingPolicy.SECURITY),
                auth.getClient().getAttribute(BrokerLinkingPolicy.SECURITY),
                user == null ? null : user.getFirstAttribute(BrokerLinkingPolicy.SECURITY),
                // Client note is server-set and survives first-broker resetFlow.
                auth.getClientNote(BrokerLinkingPolicy.SECURITY))
                || (user != null && user.getAttributeStream(BrokerLinkingPolicy.SECURITY).anyMatch(
                        value -> BrokerLinkingPolicy.strict(value)))
                // A requested assurance level/action is not satisfied by this email flow.
                || auth.getClientNote("acr_values") != null
                || auth.getClientNote("kc_action") != null;
    }
}
