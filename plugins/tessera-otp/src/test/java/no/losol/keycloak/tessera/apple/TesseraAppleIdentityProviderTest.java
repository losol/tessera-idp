// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0
package no.losol.keycloak.tessera.apple;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.broker.provider.AuthenticationRequest;
import org.keycloak.broker.provider.util.IdentityBrokerState;
import org.keycloak.common.util.Base64Url;
import org.keycloak.crypto.AsymmetricSignatureVerifierContext;
import org.keycloak.crypto.KeyWrapper;
import org.keycloak.crypto.SignatureProvider;
import org.keycloak.crypto.ECDSASignatureVerifierContext;
import org.keycloak.common.crypto.CryptoIntegration;
import org.keycloak.jose.jws.JWSInput;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.IdentityProviderSyncMode;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.UserModel;
import org.keycloak.representations.IDToken;
import org.keycloak.representations.JsonWebToken;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.util.JsonSerialization;
import no.losol.keycloak.tessera.broker.ValidatedBrokerIdentity;

class TesseraAppleIdentityProviderTest {
    private static KeyPair signingKey;
    private KeycloakSession session;
    private AuthenticationSessionModel authSession;
    private TestApple provider;

    @BeforeAll static void keys() throws Exception {
        CryptoIntegration.init(TesseraAppleIdentityProviderTest.class.getClassLoader());
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        signingKey = generator.generateKeyPair();
    }

    @BeforeEach void setup() throws Exception {
        session = mock(KeycloakSession.class);
        KeycloakContext context = mock(KeycloakContext.class);
        authSession = mock(AuthenticationSessionModel.class);
        when(session.getContext()).thenReturn(context);
        when(context.getAuthenticationSession()).thenReturn(authSession);
        when(authSession.getClientNote("BROKER_NONCE")).thenReturn("expected-nonce");
        SignatureProvider signatures = mock(SignatureProvider.class);
        when(session.getProvider(SignatureProvider.class, "RS256")).thenReturn(signatures);
        KeyWrapper key = new KeyWrapper();
        key.setPublicKey(signingKey.getPublic());
        key.setAlgorithm("RS256");
        key.setKid("fixture");
        when(signatures.verifier(key)).thenReturn(new AsymmetricSignatureVerifierContext(key));
        provider = new TestApple(session, model(), key);
    }

    private IdentityProviderModel model() {
        IdentityProviderModel model = new IdentityProviderModel();
        model.setAlias("apple");
        model.setEnabled(true);
        model.setProviderId(TesseraAppleIdentityProviderFactory.PROVIDER_ID);
        model.getConfig().put("clientId", "test.services.id");
        return model;
    }

    private Map<String, Object> claims() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", TesseraAppleIdentityProvider.ISSUER);
        claims.put("sub", "stable-apple-subject");
        claims.put("aud", "test.services.id");
        claims.put("iat", Instant.now().getEpochSecond());
        claims.put("exp", Instant.now().getEpochSecond() + 300);
        claims.put("nonce", "expected-nonce");
        claims.put("email", "relay@privaterelay.appleid.com");
        claims.put("email_verified", true);
        return claims;
    }

    private String jwt(Map<String, Object> claims) throws Exception {
        String input = Base64Url.encode("{\"alg\":\"RS256\",\"kid\":\"fixture\"}".getBytes(StandardCharsets.UTF_8))
                + "." + Base64Url.encode(JsonSerialization.writeValueAsBytes(claims));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(signingKey.getPrivate());
        signer.update(input.getBytes(StandardCharsets.UTF_8));
        return input + "." + Base64Url.encode(signer.sign());
    }

    private BrokeredIdentityContext identity(Map<String, Object> claims, String profile) throws Exception {
        String response = JsonSerialization.writeValueAsString(Map.of(
                "access_token", "synthetic-access-token", "token_type", "Bearer", "id_token", jwt(claims)));
        return provider.getFederatedIdentity(profile, response);
    }

    @ParameterizedTest @ValueSource(strings = {"aud", "iss", "azp", "exp", "zero-exp", "negative-exp", "sub", "missing-exp"})
    void rejectsInvalidValidatedClaims(String field) throws Exception {
        Map<String, Object> claims = claims();
        switch (field) {
            case "exp" -> claims.put("exp", Instant.now().getEpochSecond() - 60);
            case "zero-exp" -> claims.put("exp", 0);
            case "negative-exp" -> claims.put("exp", -1);
            case "sub" -> claims.put("sub", "");
            case "missing-exp" -> claims.remove("exp");
            default -> claims.put(field, "wrong-value");
        }
        assertThrows(IdentityBrokerException.class, () -> provider.validateToken(jwt(claims)));
    }

    @Test void validatesRealSignatureAndRejectsTampering() throws Exception {
        String token = jwt(claims());
        assertEquals("stable-apple-subject", provider.validateToken(token).getSubject());
        String[] parts = token.split("\\.");
        byte[] signature = Base64Url.decode(parts[2]);
        signature[0] ^= 1;
        assertThrows(IdentityBrokerException.class,
                () -> provider.validateToken(parts[0] + "." + parts[1] + "." + Base64Url.encode(signature)));
        assertThrows(RuntimeException.class, () -> provider.validateToken("not-a-token"));
    }

    @ParameterizedTest @ValueSource(strings = {"wrong", "missing"})
    void rejectsInvalidNonceThroughInheritedLifecycle(String mode) throws Exception {
        Map<String, Object> claims = claims();
        if (mode.equals("missing")) claims.remove("nonce");
        else claims.put("nonce", "different");
        BrokeredIdentityContext identity = identity(claims, null);
        assertThrows(RuntimeException.class, () -> provider.preprocessFederatedIdentity(session, null, identity));
    }

    @Test void acceptsNonceAndFirstConsentButNeverUsesUnsignedProfileEmail() throws Exception {
        BrokeredIdentityContext identity = identity(claims(),
                "{\"name\":{\"firstName\":\"Ada\",\"lastName\":\"Example\"},\"email\":\"attacker@example.test\"}");
        assertDoesNotThrow(() -> provider.preprocessFederatedIdentity(session, null, identity));
        assertEquals("Ada", identity.getFirstName());
        assertEquals("Example", identity.getLastName());
        assertEquals("relay@privaterelay.appleid.com", identity.getEmail());
        assertNotNull(identity.getContextData().get(TesseraAppleIdentityProvider.VALIDATED_ID_TOKEN));
    }

    @Test void returningSubjectWorksWithoutEmailOrProfile() throws Exception {
        Map<String, Object> claims = claims();
        claims.remove("email");
        claims.remove("email_verified");
        BrokeredIdentityContext identity = identity(claims, null);
        assertEquals("stable-apple-subject", identity.getId());
        assertNull(identity.getEmail());
        assertNull(identity.getFirstName());
        assertDoesNotThrow(() -> provider.preprocessFederatedIdentity(session, null, identity));
        assertEquals("stable-apple-subject", ValidatedBrokerIdentity.from(identity, false).subject());
        assertThrows(IdentityBrokerException.class, () -> ValidatedBrokerIdentity.from(identity, true));
    }

    @Test void malformedProfileFailsSafely() {
        assertThrows(Exception.class, () -> identity(claims(), "{broken"));
    }

    @ParameterizedTest @ValueSource(strings = {"privaterelay.appleid.com", "private.icloud.com", "example.test"})
    void trustsOnlyExactVerifiedClaimAndPreservesEmail(String domain) throws Exception {
        for (Object verified : new Object[] {Boolean.TRUE, "true", Boolean.FALSE, "false", "TRUE", 1, "1"}) {
            Map<String, Object> claims = claims();
            claims.put("email", "opaque@" + domain);
            claims.put(IDToken.EMAIL_VERIFIED, verified);
            BrokeredIdentityContext identity = identity(claims, null);
            UserModel user = mock(UserModel.class);
            provider.setEmailVerified(user, identity);
            verify(user).setEmailVerified(Boolean.TRUE.equals(verified) || "true".equals(verified));
            assertEquals("opaque@" + domain, identity.getEmail());
            assertEquals(Boolean.TRUE.equals(verified) || "true".equals(verified),
                    ValidatedBrokerIdentity.from(identity, true).trustsEmail());
        }
    }

    @Test void securityOptionsCannotDisableValidationOrForceProfileUpdates() {
        IdentityProviderModel model = model();
        model.getConfig().putAll(Map.of("disableNonce", "true", "validateSignature", "false",
                "issuer", "https://untrusted.test", "authorizationUrl", "https://untrusted.test",
                "tokenExchangeAccountLinkingEnabled", "true"));
        model.setSyncMode(IdentityProviderSyncMode.FORCE);
        TesseraAppleIdentityProvider secure = new TesseraAppleIdentityProvider(session, model);
        assertFalse(secure.getConfig().isDisableNonce());
        assertTrue(secure.getConfig().isValidateSignature());
        assertTrue(secure.getConfig().isUseJwksUrl());
        assertFalse(secure.getConfig().isTokenExchangeAccountLinkingEnabled());
        assertEquals(TesseraAppleIdentityProvider.ISSUER, secure.getConfig().getIssuer());
        assertEquals(IdentityProviderSyncMode.IMPORT, secure.getConfig().getSyncMode());
        assertEquals("true", model.getConfig().get("disableNonce"), "input configuration must stay unchanged");
    }

    @Test void rejectsEveryExchangeEntryPoint() {
        assertFalse(provider.supportsExternalExchange());
        assertThrows(IdentityBrokerException.class, () -> provider.exchangeExternalImpl(null, null));
        assertThrows(IdentityBrokerException.class, () -> provider.exchangeExternalTokenV1Impl(null, null));
    }

    @Test void authorizationUsesFormPostCodeFlowAndFreshNonce() {
        AuthenticationRequest request = mock(AuthenticationRequest.class);
        IdentityBrokerState state = mock(IdentityBrokerState.class);
        when(request.getAuthenticationSession()).thenReturn(authSession);
        when(request.getState()).thenReturn(state);
        when(state.getEncoded()).thenReturn("synthetic-state");
        when(request.getRedirectUri()).thenReturn("https://idp.example.test/realms/test/broker/apple/endpoint");
        String query = provider.authorization(request).getRawQuery();
        Map<String, String> parameters = new HashMap<>();
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            parameters.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        assertEquals("form_post", parameters.get("response_mode"));
        assertEquals("code", parameters.get("response_type"));
        assertEquals("synthetic-state", parameters.get("state"));
        assertEquals("test.services.id", parameters.get("client_id"));
        assertEquals("https://idp.example.test/realms/test/broker/apple/endpoint", parameters.get("redirect_uri"));
        assertEquals("openid name email", parameters.get("scope"));
        assertNotNull(parameters.get("nonce"));
        verify(authSession).setClientNote("BROKER_NONCE", parameters.get("nonce"));
    }

    @Test void signsClientSecretAndRotatesSyntheticP8WithoutMutatingStoredKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        for (String keyId : new String[] {"original-key", "replacement-key"}) {
            KeyPair pair = generator.generateKeyPair();
            String p8 = "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----";
            IdentityProviderModel stored = model();
            stored.getConfig().putAll(Map.of("clientSecret", p8, "teamId", "fixture-team", "keyId", keyId));
            TesseraAppleIdentityProvider signer = new TesseraAppleIdentityProvider(session, stored);
            signer.prepareClientSecret("test.services.id");
            JWSInput jws = new JWSInput(signer.getConfig().getClientSecret());
            JsonWebToken secret = jws.readJsonContent(JsonWebToken.class);
            assertEquals("ES256", jws.getHeader().getRawAlgorithm());
            assertEquals(keyId, jws.getHeader().getKeyId());
            assertEquals("fixture-team", secret.getIssuer());
            assertEquals("test.services.id", secret.getSubject());
            assertTrue(secret.hasAudience(TesseraAppleIdentityProvider.ISSUER));
            assertEquals(180 * 86400L, secret.getExp() - secret.getIat());
            KeyWrapper publicKey = new KeyWrapper();
            publicKey.setAlgorithm("ES256");
            publicKey.setPublicKey(pair.getPublic());
            assertTrue(new ECDSASignatureVerifierContext(publicKey).verify(
                    jws.getEncodedSignatureInput().getBytes(StandardCharsets.UTF_8), jws.getSignature()));
            assertEquals(p8, stored.getConfig().get("clientSecret"));
        }
    }

    private static class TestApple extends TesseraAppleIdentityProvider {
        private final KeyWrapper key;
        TestApple(KeycloakSession session, IdentityProviderModel model, KeyWrapper key) {
            super(session, model);
            this.key = key;
        }
        @Override protected KeyWrapper getIdentityProviderKeyWrapper(JWSInput input) { return key; }
        java.net.URI authorization(AuthenticationRequest request) { return createAuthorizationUrl(request).build(); }
    }
}
