// SPDX-License-Identifier: MPL-2.0
package no.losol.keycloak.tessera.broker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.authenticators.broker.IdpEmailVerificationAuthenticator;
import org.keycloak.authentication.authenticators.broker.util.SerializedBrokeredIdentityContext;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.email.EmailTemplateProvider;
import org.keycloak.models.UserModel;
import org.keycloak.protocol.LoginProtocol;

class BrokerEmailResendTest {
    @Test void nativeSuppressionPreventsRefreshButResendSendsSecondMessage() throws Exception {
        var context = mock(AuthenticationFlowContext.class, RETURNS_DEEP_STUBS);
        var broker = BrokerLinkingPolicyTest.broker("google", "existing@example.org", true, null);
        broker.setUsername("existing@example.org");
        var session = context.getSession();
        var realm = context.getRealm();
        var auth = context.getAuthenticationSession();
        var user = mock(UserModel.class, RETURNS_DEEP_STUBS);
        when(user.getId()).thenReturn("original-account");
        when(user.getUsername()).thenReturn("original-user");
        when(user.getEmail()).thenReturn("existing@example.org");
        when(user.isEnabled()).thenReturn(true);
        when(user.isEmailVerified()).thenReturn(true);
        when(user.credentialManager().getStoredCredentialsStream()).thenAnswer(x -> Stream.empty());
        when(user.getRequiredActionsStream()).thenAnswer(x -> Stream.empty());
        when(user.getAttributeStream(anyString())).thenAnswer(x -> Stream.empty());
        when(session.users().searchForUserStream(any(), anyMap(), isNull(), isNull())).thenAnswer(x -> Stream.of(user));
        when(session.users().getUserByUsername(any(), anyString())).thenReturn(null);
        when(session.users().getFederatedIdentity(any(), eq(user), anyString())).thenReturn(null);
        when(session.users().getUserById(realm, "original-account")).thenReturn(user);
        when(realm.getSmtpConfig()).thenReturn(Map.of("host", "fixture"));
        when(realm.getName()).thenReturn("broker-fixture");
        when(session.getContext().getRealm()).thenReturn(realm);
        when(session.getContext().getUri().getBaseUri()).thenReturn(URI.create("http://localhost/"));
        when(session.tokens().encode(any())).thenReturn("signed-fixture-token");
        when(auth.getParentSession().getId()).thenReturn("root-session");
        when(auth.getTabId()).thenReturn("tab");
        when(auth.getClient().getId()).thenReturn("client-id");
        when(auth.getClient().getClientId()).thenReturn("fixture");
        when(auth.getProtocol()).thenReturn("openid-connect");
        when(context.getExecution().getId()).thenReturn("execution");
        var protocol = mock(LoginProtocol.class, RETURNS_DEEP_STUBS);
        doReturn(protocol).when(session).getProvider(LoginProtocol.class, "openid-connect");
        when(protocol.getClientData(auth).encode()).thenReturn("fixture-data");
        Map<String, String> notes = new HashMap<>();
        when(auth.getAuthNote(anyString())).thenAnswer(x -> notes.get(x.getArgument(0)));
        doAnswer(x -> { notes.put(x.getArgument(0), x.getArgument(1)); return null; }).when(auth).setAuthNote(anyString(), anyString());
        doAnswer(x -> { notes.remove(x.getArgument(0)); return null; }).when(auth).removeAuthNote(anyString());
        var template = mock(EmailTemplateProvider.class, RETURNS_DEEP_STUBS);
        doReturn(template).when(session).getProvider(EmailTemplateProvider.class);
        var mail = template.setRealm(realm).setAuthenticationSession(auth)
                .setUser(user).setAttribute(EmailTemplateProvider.IDENTITY_PROVIDER_BROKER_CONTEXT, broker);
        var serialized = new SerializedBrokeredIdentityContext();
        // Exercise Keycloak's real native send/suppression/action implementation;
        // only page rendering and token signing are mocked infrastructure.
        var nativeVerification = new IdpEmailVerificationAuthenticator() {
            @Override public void authenticate(AuthenticationFlowContext c) { super.authenticateImpl(c, serialized, broker); }
            @Override public void action(AuthenticationFlowContext c) { super.actionImpl(c, serialized, broker); }
            @Override protected void showEmailSentPage(AuthenticationFlowContext c, BrokeredIdentityContext b) { }
        };
        var linking = new TesseraBrokerLinking(nativeVerification);
        linking.authenticateImpl(context, serialized, broker);
        verify(mail).sendConfirmIdentityBrokerLink(anyString(), anyLong());
        linking.authenticateImpl(context, serialized, broker);
        verify(mail, times(1)).sendConfirmIdentityBrokerLink(anyString(), anyLong());
        linking.actionImpl(context, serialized, broker);
        verify(mail, times(2)).sendConfirmIdentityBrokerLink(anyString(), anyLong());
        verify(context, never()).success();
    }
}
