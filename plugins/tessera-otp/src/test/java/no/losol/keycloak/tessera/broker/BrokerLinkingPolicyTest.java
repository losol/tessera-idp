// SPDX-License-Identifier: MPL-2.0
package no.losol.keycloak.tessera.broker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static no.losol.keycloak.tessera.broker.BrokerLinkingPolicy.Decision.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.keycloak.broker.oidc.OIDCIdentityProvider;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.representations.JsonWebToken;

class BrokerLinkingPolicyTest {
    static Stream<Arguments> trustCases() {
        return Stream.of(
            Arguments.of("google", "alice@gmail.com", true, null, true),
            Arguments.of("google", "alice@GMAIL.COM", true, null, true),
            Arguments.of("google", "alice@company.example", true, "company.example", true),
            Arguments.of("google", "alice@company.example", true, null, false),
            Arguments.of("google", "alice@company.example", true, " ", false),
            Arguments.of("google", "alice@gmail.com", false, null, false),
            Arguments.of("google", "alice@gmail.com", "true", null, false),
            Arguments.of("tessera-apple", "id@privaterelay.appleid.com", true, null, true),
            Arguments.of("tessera-apple", "id@private.icloud.com", "true", null, true),
            Arguments.of("tessera-apple", "alice@example.org", "TRUE", null, false),
            Arguments.of("tessera-apple", "alice@example.org", "false", null, false),
            Arguments.of("tessera-apple", "alice@example.org", 1, null, false),
            Arguments.of("tessera-apple", "alice@example.org", null, null, false));
    }

    @ParameterizedTest @MethodSource("trustCases")
    void trustOnlyAuthoritativeValidatedEmail(String provider, String email, Object verified, String hd, boolean trusted) {
        var context = broker(provider, email, verified, hd);
        var identity = ValidatedBrokerIdentity.from(context, true);
        assertEquals(trusted, identity.trustsEmail());
        assertEquals(trusted ? CREATE : VERIFY_EMAIL,
                BrokerLinkingPolicy.decide(identity, 0, false, false, false, false));
        assertEquals(trusted ? AUTO_LINK : VERIFY_EMAIL,
                BrokerLinkingPolicy.decide(identity, 1, true, false, false, false));
    }

    @Test void ambiguousConflictingUnverifiedAndProtectedAccountsFailClosed() {
        var identity = ValidatedBrokerIdentity.from(broker("google", "a@gmail.com", true, null), true);
        assertEquals(REJECT, BrokerLinkingPolicy.decide(identity, 2, true, false, false, false));
        assertEquals(REJECT, BrokerLinkingPolicy.decide(identity, 1, false, false, false, false));
        assertEquals(REJECT, BrokerLinkingPolicy.decide(identity, 1, true, true, false, false));
        assertEquals(REJECT, BrokerLinkingPolicy.decide(identity, 1, true, false, true, false));
        assertEquals(REJECT, BrokerLinkingPolicy.decide(identity, 0, false, false, false, true));
    }

    @Test void stricterSettingAlwaysWinsIncludingInvalidConfiguration() {
        assertFalse(BrokerLinkingPolicy.strict(null, "standard", null));
        for (int i = 0; i < 4; i++) {
            String[] settings = {"standard", "standard", "standard", "standard"};
            settings[i] = "strict";
            assertTrue(BrokerLinkingPolicy.strict(settings));
            settings[i] = "typo";
            assertTrue(BrokerLinkingPolicy.strict(settings));
        }
    }

    @Test void returningIdentityNeedsSubjectButNotEmailOrProfile() {
        var context = broker("tessera-apple", null, null, null);
        assertEquals("stable-subject", ValidatedBrokerIdentity.from(context, false).subject());
        assertThrows(IdentityBrokerException.class, () -> ValidatedBrokerIdentity.from(context, true));
        context.setEmail("a-different-local-profile@example.org");
        assertEquals("stable-subject", ValidatedBrokerIdentity.from(context, false).subject());
    }

    @Test void browserOrMapperClaimsCannotReplaceValidatedClaims() {
        var context = broker("google", "a@gmail.com", false, null);
        context.getContextData().put("email_verified", true);
        assertFalse(ValidatedBrokerIdentity.from(context, true).trustsEmail());
        context.setEmail("changed@gmail.com");
        assertThrows(IdentityBrokerException.class, () -> ValidatedBrokerIdentity.from(context, true));
        context.setId("different-subject");
        assertThrows(IdentityBrokerException.class, () -> ValidatedBrokerIdentity.from(context, false));
    }

    @Test void unsupportedIssuerProviderOrMissingValidatedTokenFailClosed() {
        var context = broker("google", "a@gmail.com", true, null);
        ((JsonWebToken) context.getContextData().get(OIDCIdentityProvider.VALIDATED_ID_TOKEN)).issuer("https://attacker.example");
        assertThrows(IdentityBrokerException.class, () -> ValidatedBrokerIdentity.from(context, true));
        context.getContextData().remove(OIDCIdentityProvider.VALIDATED_ID_TOKEN);
        assertThrows(IdentityBrokerException.class, () -> ValidatedBrokerIdentity.from(context, true));
        assertThrows(IdentityBrokerException.class,
                () -> ValidatedBrokerIdentity.from(broker("oidc", "a@gmail.com", true, null), true));
    }

    static BrokeredIdentityContext broker(String provider, String email, Object verified, String hd) {
        var config = new IdentityProviderModel();
        config.setProviderId(provider);
        config.setAlias(provider.equals("google") ? "google" : "apple");
        config.setEnabled(true);
        config.getConfig().put("syncMode", "IMPORT");
        config.getConfig().put("validateSignature", "true");
        var context = new BrokeredIdentityContext("stable-subject", config);
        context.setEmail(email);
        var token = new JsonWebToken();
        token.issuer(provider.equals("google") ? ValidatedBrokerIdentity.GOOGLE_ISSUER : ValidatedBrokerIdentity.APPLE_ISSUER);
        token.subject("stable-subject");
        if (email != null) token.getOtherClaims().put("email", email);
        if (verified != null) token.getOtherClaims().put("email_verified", verified);
        if (hd != null) token.getOtherClaims().put("hd", hd);
        context.getContextData().put(OIDCIdentityProvider.VALIDATED_ID_TOKEN, token);
        return context;
    }
}
