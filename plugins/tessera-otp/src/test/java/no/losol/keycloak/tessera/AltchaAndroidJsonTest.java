// tessera-otp · passwordless one-time-code authenticator for Keycloak
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

package no.losol.keycloak.tessera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.altcha.altcha.v2.Altcha;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * Guards the ALTCHA ↔ JSON wiring in the shaded provider JAR.
 *
 * <p>ALTCHA's Java library calls the {@code org.json.*} API to parse the base64
 * solution payload. Those classes are supplied by the shaded <em>android-json</em>
 * (Apache-2.0) instead of the reference {@code org.json:json} — whose non-free
 * "JSON License" we avoid (see {@code pom.xml}). android-json is a frozen 2014
 * snapshot, so this test drives a full create → solve → verify round-trip through
 * the real library, exercising the {@code verifySolution(String, ...)} and
 * {@code parsePayload(String)} paths production uses. Any {@code org.json} method
 * android-json is missing surfaces here as a build failure rather than a broken
 * live login.
 */
class AltchaAndroidJsonTest {

    private static final String SECRET = "test-hmac-key";

    @Test
    void createSolveVerifyRoundTripParsesPayloadViaAndroidJson() throws Exception {
        Altcha.KeyDerivationFunction kdf = Altcha.kdf("SHA-256");

        // Low KDF cost keeps each solve iteration cheap so the test is fast.
        Altcha.Challenge challenge = Altcha.createChallenge(
                new Altcha.CreateChallengeOptions()
                        .algorithm("SHA-256")
                        .cost(1000)
                        .hmacSignatureSecret(SECRET)
                        .expiresInSeconds(300));

        // toJson() is what the authenticator hands to the login form; it is the
        // nested {"parameters":{...},"signature":...} object.
        String challengeJson = challenge.toJson();
        assertNotNull(challengeJson);

        // Solve the proof-of-work the way the browser widget would.
        Altcha.Solution solution = Altcha.solveChallenge(challenge, kdf);
        assertNotNull(solution, "challenge should be solvable");

        // Assemble the base64 payload the widget submits — the challenge object
        // plus the solution. Building and later parsing it goes through
        // android-json's JSONObject (put/getString/getInt/getLong/...).
        String base64Payload = Base64.getEncoder().encodeToString(
                new JSONObject()
                        .put("challenge", new JSONObject(challengeJson))
                        .put("solution", new JSONObject()
                                .put("counter", solution.counter())
                                .put("derivedKey", solution.derivedKey())
                                .put("time", solution.time()))
                        .toString()
                        .getBytes(StandardCharsets.UTF_8));

        // Production path: verifySolution(String, ...) decodes and parses the
        // payload with org.json — i.e. the shaded android-json.
        Altcha.VerifySolutionResult result =
                Altcha.verifySolution(base64Payload, SECRET, kdf);
        assertTrue(result.verified(), "a correctly solved payload must verify");
        assertFalse(result.expired());

        // parsePayload backs the authenticator's replay-id extraction; exercise
        // it too and confirm the salt round-trips (mirrors TesseraOtpAuthenticator).
        Altcha.Payload parsed = Altcha.parsePayload(base64Payload);
        assertEquals(
                challenge.parameters().salt(),
                parsed.challenge().parameters().salt());
    }
}
