// SPDX-License-Identifier: MPL-2.0
package no.losol.keycloak.tessera.broker;

import jakarta.ws.rs.core.Response;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.authenticators.broker.util.PostBrokerLoginConstants;
import org.keycloak.authentication.authenticators.broker.util.SerializedBrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

/** REQUIRED post-broker execution: also runs for returning linked subjects. */
public final class TesseraBrokerPolicy implements Authenticator {
    @Override public void authenticate(AuthenticationFlowContext context) {
        try {
            var serialized = SerializedBrokeredIdentityContext.readFromAuthenticationSession(
                    context.getAuthenticationSession(), PostBrokerLoginConstants.PBL_BROKERED_IDENTITY_CONTEXT);
            if (serialized == null) throw new IdentityBrokerException("Missing post-broker context");
            var broker = serialized.deserialize(context.getSession(), context.getAuthenticationSession());
            BrokerPolicySupport.configuration(broker);
            var identity = ValidatedBrokerIdentity.from(broker, false);
            var user = context.getUser();
            var link = user == null ? null : context.getSession().users().getFederatedIdentity(
                    context.getRealm(), user, broker.getIdpConfig().getAlias());
            if (link == null || !identity.subject().equals(link.getUserId())
                    || BrokerPolicySupport.protectedAccount(user) || BrokerPolicySupport.strict(context, user)) {
                throw new IdentityBrokerException("Broker alone cannot satisfy this account's policy");
            }
            context.success();
        } catch (IdentityBrokerException e) {
            context.failure(AuthenticationFlowError.IDENTITY_PROVIDER_ERROR,
                    context.form().setError("tesseraBrokerRejected").createErrorPage(Response.Status.FORBIDDEN));
        }
    }
    @Override public void action(AuthenticationFlowContext context) { authenticate(context); }
    @Override public boolean requiresUser() { return true; }
    @Override public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) { return true; }
    @Override public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) { }
    @Override public void close() { }
}
