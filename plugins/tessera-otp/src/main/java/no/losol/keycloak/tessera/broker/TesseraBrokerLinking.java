// SPDX-License-Identifier: MPL-2.0
package no.losol.keycloak.tessera.broker;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import jakarta.ws.rs.core.Response;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.FlowStatus;
import org.keycloak.authentication.authenticators.broker.AbstractIdpAuthenticator;
import org.keycloak.authentication.authenticators.broker.IdpEmailVerificationAuthenticator;
import org.keycloak.authentication.authenticators.broker.util.ExistingUserInfo;
import org.keycloak.authentication.authenticators.broker.util.SerializedBrokeredIdentityContext;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

/** Sole REQUIRED execution in the Tessera first broker login flow. */
public final class TesseraBrokerLinking extends AbstractIdpAuthenticator {
    private static final String PENDING_USER = "tessera.broker.pending-user";
    private final IdpEmailVerificationAuthenticator verification;

    public TesseraBrokerLinking() { this(new IdpEmailVerificationAuthenticator()); }
    TesseraBrokerLinking(IdpEmailVerificationAuthenticator verification) { this.verification = verification; }

    @Override
    protected void authenticateImpl(AuthenticationFlowContext context, SerializedBrokeredIdentityContext serialized,
                                    BrokeredIdentityContext broker) {
        process(context, serialized, broker, false);
    }

    private void process(AuthenticationFlowContext context, SerializedBrokeredIdentityContext serialized,
                         BrokeredIdentityContext broker, boolean resend) {
        try {
            BrokerPolicySupport.configuration(broker);
            var identity = ValidatedBrokerIdentity.from(broker, true);
            var users = context.getSession().users();
            var realm = context.getRealm();
            var auth = context.getAuthenticationSession();
            List<UserModel> matches;
            try (var found = users.searchForUserStream(realm,
                    Map.of(UserModel.EMAIL, identity.email(), UserModel.EXACT, "true"), null, null)) {
                matches = found.filter(u -> identity.email().equalsIgnoreCase(u.getEmail())).limit(2).toList();
            }
            UserModel user = matches.size() == 1 ? matches.get(0) : null;
            UserModel usernameMatch = users.getUserByUsername(realm, identity.email());
            boolean conflict = usernameMatch != null && (user == null || !usernameMatch.getId().equals(user.getId()));
            if (user != null) {
                // Do not replace an existing provider link, even after fresh email proof.
                conflict |= users.getFederatedIdentity(realm, user, broker.getIdpConfig().getAlias()) != null;
            }
            boolean pending = user != null && user.getId().equals(auth.getAuthNote(PENDING_USER));
            var decision = BrokerLinkingPolicy.decide(identity, matches.size(),
                    user != null && (user.isEmailVerified() || pending),
                    BrokerPolicySupport.protectedAccount(user), conflict, BrokerPolicySupport.strict(context, user));
            if (decision == BrokerLinkingPolicy.Decision.REJECT) {
                reject(context);
                return;
            }
            if (user == null) {
                // Random internal username cannot shadow any email/username. The
                // verified email is the login identifier; citizen data stays local.
                user = users.addUser(realm, "tessera-" + UUID.randomUUID());
                user.setEnabled(true);
                user.setEmail(identity.email());
                user.setFirstName(broker.getFirstName());
                user.setLastName(broker.getLastName());
                user.setEmailVerified(identity.trustsEmail());
                auth.setAuthNote(PENDING_USER, user.getId());
            }
            if (decision == BrokerLinkingPolicy.Decision.VERIFY_EMAIL) {
                if (realm.getSmtpConfig().isEmpty()) {
                    reject(context);
                    return;
                }
                // Keycloak's signed, expiring, one-use action token binds this
                // exact account, email, provider alias, subject and auth session.
                auth.setAuthNote(EXISTING_USER_INFO,
                        new ExistingUserInfo(user.getId(), UserModel.EMAIL, user.getEmail()).serialize());
                if (resend) verification.action(context);
                else verification.authenticate(context);
                // The native handler reports SUCCESS only after consuming the
                // signed, account/email/subject/session-bound email proof. In
                // this same-email-only flow that also verifies the local email;
                // do not ask a new account to prove the identical address twice.
                // No user required actions or stronger protections are removed.
                if (context.getStatus() == FlowStatus.SUCCESS && context.getUser() != null
                        && user.getId().equals(context.getUser().getId())
                        && identity.email().equalsIgnoreCase(user.getEmail())) {
                    user.setEmailVerified(true);
                    auth.removeRequiredAction(UserModel.RequiredAction.VERIFY_EMAIL);
                }
                return;
            }
            // Existing accounts are selected as-is: no profile, email, citizen
            // attributes, credentials or required actions are copied/removed.
            context.setUser(user);
            context.success();
        } catch (org.keycloak.broker.provider.IdentityBrokerException | ModelDuplicateException e) {
            reject(context);
        }
    }

    private static void reject(AuthenticationFlowContext context) {
        context.failure(AuthenticationFlowError.IDENTITY_PROVIDER_ERROR,
                context.form().setError("tesseraBrokerRejected").createErrorPage(Response.Status.FORBIDDEN));
    }

    @Override
    protected void actionImpl(AuthenticationFlowContext context, SerializedBrokeredIdentityContext serialized,
                              BrokeredIdentityContext broker) {
        // Only the signed native verification handler may authorize continuation;
        // a posted email/profile/submit value never chooses the linked account.
        process(context, serialized, broker, true);
    }
    @Override public boolean requiresUser() { return false; }
    @Override public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) { return true; }
}
