// tessera · Sign in with Apple identity provider for Keycloak
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

package no.losol.keycloak.tessera.apple;

import org.keycloak.common.util.Base64;
import org.keycloak.common.util.Time;
import org.keycloak.crypto.Algorithm;
import org.keycloak.crypto.SignatureException;
import org.keycloak.crypto.SignatureSignerContext;
import org.keycloak.jose.jws.JWSBuilder;
import org.keycloak.representations.JsonWebToken;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;

/**
 * Apple does not take a static client secret at its token endpoint. The
 * "secret" is a short-lived JWT the client signs with the ES256 key Apple
 * issued for the team: {@code iss} = Team ID, {@code sub} = Services ID
 * (the client id), {@code aud} = Apple's issuer, {@code kid} = Key ID.
 * Apple accepts a lifetime of up to six months; a fresh one is signed per
 * token request instead, so there is nothing to rotate except the key
 * itself.
 *
 * @see <a href="https://developer.apple.com/documentation/accountorganizationaldatasharing/creating-a-client-secret">Apple: Creating a client secret</a>
 */
final class AppleClientSecret {

    /** Lifetime of a signed secret; it only has to outlive the token request. */
    static final int LIFETIME_SECONDS = 300;

    private AppleClientSecret() {
    }

    /**
     * Signs a client secret.
     *
     * @param privateKey the {@code .p8} key Apple issued, PEM or bare base64.
     *                   Header/footer lines and all whitespace are ignored, so
     *                   the file can be pasted into a single-line field.
     */
    static String sign(String privateKey, String keyId, String teamId, String clientId) {
        PrivateKey key = parsePrivateKey(privateKey);

        JsonWebToken claims = new JsonWebToken();
        claims.issuer(teamId);
        claims.subject(clientId);
        claims.audience(AppleIdentityProvider.ISSUER);
        claims.iat((long) Time.currentTime());
        claims.exp((long) Time.currentTime() + LIFETIME_SECONDS);

        return new JWSBuilder().jsonContent(claims).sign(new Es256Signer(key, keyId));
    }

    /**
     * ES256 with the JDK alone. JOSE wants the raw {@code r||s} signature,
     * which the JDK produces directly in P1363 format, so none of Keycloak's
     * key-provider machinery is needed for this one key.
     */
    private record Es256Signer(PrivateKey key, String kid) implements SignatureSignerContext {

        @Override
        public String getKid() {
            return kid;
        }

        @Override
        public String getAlgorithm() {
            return Algorithm.ES256;
        }

        @Override
        public String getHashAlgorithm() {
            return "SHA-256";
        }

        @Override
        public byte[] sign(byte[] data) throws SignatureException {
            try {
                Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
                signature.initSign(key);
                signature.update(data);
                return signature.sign();
            } catch (GeneralSecurityException e) {
                throw new SignatureException("Signing the Apple client secret failed", e);
            }
        }
    }

    static PrivateKey parsePrivateKey(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalArgumentException("Apple private key is not configured");
        }
        String base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(base64)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Apple private key is not a PKCS#8 EC key", e);
        }
    }
}
