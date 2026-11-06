// SPDX-License-Identifier: MPL-2.0
package no.losol.keycloak.tessera.broker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.authenticators.broker.util.SerializedBrokeredIdentityContext;
import org.keycloak.models.UserModel;
import org.keycloak.authentication.FlowStatus;
import org.keycloak.authentication.authenticators.broker.IdpEmailVerificationAuthenticator;

class BrokerFlowTest {
    @Test void onlyNativeSuccessfulSameAccountProofSatisfiesTheEmailAction() {
        for (FlowStatus status : new FlowStatus[]{FlowStatus.CHALLENGE, FlowStatus.SUCCESS}) {
            var context = mock(AuthenticationFlowContext.class, RETURNS_DEEP_STUBS);
            var broker = BrokerLinkingPolicyTest.broker("google", "pending@example.org", true, null);
            var user = mock(UserModel.class, RETURNS_DEEP_STUBS);
            when(user.getId()).thenReturn("pending-id");
            when(user.getEmail()).thenReturn("pending@example.org");
            when(user.isEnabled()).thenReturn(true);
            when(user.credentialManager().getStoredCredentialsStream()).thenAnswer(x -> Stream.empty());
            when(user.getRequiredActionsStream()).thenAnswer(x -> Stream.empty());
            when(user.getAttributeStream(anyString())).thenAnswer(x -> Stream.empty());
            when(context.getSession().users().searchForUserStream(any(), anyMap(), isNull(), isNull())).thenAnswer(x -> Stream.of(user));
            when(context.getSession().users().getUserByUsername(any(), anyString())).thenReturn(null);
            when(context.getSession().users().getFederatedIdentity(any(), eq(user), anyString())).thenReturn(null);
            when(context.getAuthenticationSession().getAuthNote("tessera.broker.pending-user")).thenReturn("pending-id");
            when(context.getRealm().getSmtpConfig()).thenReturn(java.util.Map.of("host", "fixture"));
            when(context.getUser()).thenReturn(user);
            when(context.getStatus()).thenReturn(status);
            var nativeVerifier = mock(IdpEmailVerificationAuthenticator.class);
            new TesseraBrokerLinking(nativeVerifier).authenticateImpl(context, new SerializedBrokeredIdentityContext(), broker);
            verify(nativeVerifier).authenticate(context);
            if (status == FlowStatus.SUCCESS) {
                verify(user).setEmailVerified(true);
                verify(context.getAuthenticationSession()).removeRequiredAction(UserModel.RequiredAction.VERIFY_EMAIL);
            } else {
                verify(user, never()).setEmailVerified(anyBoolean());
                verify(context.getAuthenticationSession(), never()).removeRequiredAction(UserModel.RequiredAction.VERIFY_EMAIL);
            }
            verify(user, never()).removeRequiredAction(anyString());
        }
    }
    @Test void autoLinkSelectsOriginalAccountWithoutMutatingIt() {
        var context = mock(AuthenticationFlowContext.class, RETURNS_DEEP_STUBS);
        var broker = BrokerLinkingPolicyTest.broker("google", "person@gmail.com", true, null);
        var user = mock(UserModel.class, RETURNS_DEEP_STUBS);
        when(user.getId()).thenReturn("original-citizen-associated-id");
        when(user.getEmail()).thenReturn("person@gmail.com");
        when(user.isEnabled()).thenReturn(true);
        when(user.isEmailVerified()).thenReturn(true);
        when(user.credentialManager().getStoredCredentialsStream()).thenAnswer(x -> Stream.empty());
        when(user.getRequiredActionsStream()).thenAnswer(x -> Stream.empty());
        when(context.getSession().users().searchForUserStream(any(), anyMap(), isNull(), isNull()))
                .thenAnswer(x -> Stream.of(user));
        when(context.getSession().users().getUserByUsername(any(), anyString())).thenReturn(null);
        when(context.getSession().users().getFederatedIdentity(any(), eq(user), anyString())).thenReturn(null);
        new TesseraBrokerLinking().authenticateImpl(context, new SerializedBrokeredIdentityContext(), broker);
        verify(context).setUser(user);
        verify(context).success();
        verify(user, never()).setEmail(anyString());
        verify(user, never()).setEmailVerified(anyBoolean());
        verify(user, never()).setAttribute(anyString(), anyList());
        verify(context.getSession().users(), never()).addUser(any(), anyString());
    }

    @Test void forceSyncOrDisabledSignatureIsRejected() {
        var broker = BrokerLinkingPolicyTest.broker("google", "person@gmail.com", true, null);
        assertDoesNotThrow(() -> BrokerPolicySupport.configuration(broker));
        broker.getIdpConfig().getConfig().put("syncMode", "FORCE");
        assertThrows(RuntimeException.class, () -> BrokerPolicySupport.configuration(broker));
        broker.getIdpConfig().getConfig().put("syncMode", "IMPORT");
        broker.getIdpConfig().getConfig().put("validateSignature", "false");
        assertThrows(RuntimeException.class, () -> BrokerPolicySupport.configuration(broker));
        broker.getIdpConfig().getConfig().put("validateSignature", "true");
        broker.getIdpConfig().getConfig().put("disableNonce", "TRUE");
        assertThrows(RuntimeException.class, () -> BrokerPolicySupport.configuration(broker));
    }

    @Test void stricterValueAnywhereInTheUserAttributeWins() {
        var context = mock(AuthenticationFlowContext.class, RETURNS_DEEP_STUBS);
        var user = mock(UserModel.class);
        when(user.getFirstAttribute(BrokerLinkingPolicy.SECURITY)).thenReturn("standard");
        when(user.getAttributeStream(BrokerLinkingPolicy.SECURITY)).thenReturn(Stream.of("standard", "strict"));
        assertTrue(BrokerPolicySupport.strict(context, user));
    }
}
