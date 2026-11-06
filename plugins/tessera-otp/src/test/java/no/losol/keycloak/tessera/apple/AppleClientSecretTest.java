// tessera · Sign in with Apple identity provider for Keycloak
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

package no.losol.keycloak.tessera.apple;

import org.junit.jupiter.api.Test;
import org.keycloak.common.util.Base64;
import org.keycloak.common.util.Time;
import org.keycloak.jose.jws.JWSInput;
import org.keycloak.representations.JsonWebToken;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class AppleClientSecretTest {

    private static KeyPair newP256KeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    /** The key as Apple's .p8 download looks: PEM header, 64-char lines, footer. */
    private static String toPem(KeyPair keyPair) {
        String base64 = Base64.encodeBytes(keyPair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n"
                + String.join("\n", base64.split("(?<=\\G.{64})"))
                + "\n-----END PRIVATE KEY-----\n";
    }

    @Test
    void signsAnEs256JwtAppleAccepts() throws Exception {
        KeyPair keyPair = newP256KeyPair();

        String secret = AppleClientSecret.sign(toPem(keyPair), "ABC123DEFG", "TEAM567890", "no.losol.tessera.web");

        JWSInput jws = new JWSInput(secret);
        assertEquals("ES256", jws.getHeader().getAlgorithm().name());
        assertEquals("ABC123DEFG", jws.getHeader().getKeyId());

        JsonWebToken claims = jws.readJsonContent(JsonWebToken.class);
        assertEquals("TEAM567890", claims.getIssuer());
        assertEquals("no.losol.tessera.web", claims.getSubject());
        assertArrayEquals(new String[]{AppleIdentityProvider.ISSUER}, claims.getAudience());
        assertTrue(claims.isActive());
        assertTrue(claims.getExp() - claims.getIat() <= AppleClientSecret.LIFETIME_SECONDS);
        assertTrue(claims.getExp() - Time.currentTime() <= AppleClientSecret.LIFETIME_SECONDS);

        // Signature is a JOSE r||s pair (64 bytes), verifiable with the public key.
        byte[] signature = jws.getSignature();
        assertEquals(64, signature.length);
        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(keyPair.getPublic());
        verifier.update(jws.getEncodedSignatureInput().getBytes());
        assertTrue(verifier.verify(signature));
    }

    @Test
    void acceptsTheKeyWithoutPemFramingOrLineBreaks() throws Exception {
        KeyPair keyPair = newP256KeyPair();
        String pem = toPem(keyPair);
        String oneLine = pem.replace("\n", "");
        String bare = Base64.encodeBytes(keyPair.getPrivate().getEncoded());

        byte[] expected = keyPair.getPrivate().getEncoded();
        for (String variant : Arrays.asList(pem, oneLine, bare, "  " + bare + "\n")) {
            assertArrayEquals(expected, AppleClientSecret.parsePrivateKey(variant).getEncoded(), variant);
        }
    }

    @Test
    void rejectsMissingOrInvalidKeys() {
        assertThrows(IllegalArgumentException.class, () -> AppleClientSecret.parsePrivateKey(null));
        assertThrows(IllegalArgumentException.class, () -> AppleClientSecret.parsePrivateKey("   "));
        assertThrows(IllegalArgumentException.class, () -> AppleClientSecret.parsePrivateKey("not a key"));
    }
}
