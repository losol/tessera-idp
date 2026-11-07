// tessera · Sign in with Apple identity provider for Keycloak
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

package no.losol.keycloak.tessera.apple;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;
import org.keycloak.broker.provider.BrokeredIdentityContext;

import java.io.IOException;

/**
 * Apple's first-consent {@code user} form field,
 * {@code {"name":{"firstName":"…","lastName":"…"},"email":"…"}}. It is sent
 * once, on the user's first authorization, and never again.
 */
final class AppleUser {

    private static final Logger logger = Logger.getLogger(AppleUser.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private AppleUser() {
    }

    /**
     * Copies the name onto the identity. The email in the field is
     * deliberately ignored: the ID token is the only trusted source for it.
     * Anything unparseable is ignored too; the name is cosmetic.
     */
    static void applyName(BrokeredIdentityContext identity, String userJson) {
        JsonNode name;
        try {
            name = mapper.readTree(userJson).path("name");
        } catch (IOException | RuntimeException e) {
            logger.debugf(e, "Ignoring unparseable Apple user field");
            return;
        }
        String firstName = name.path("firstName").asText("").trim();
        String lastName = name.path("lastName").asText("").trim();
        if (!firstName.isEmpty()) {
            identity.setFirstName(firstName);
        }
        if (!lastName.isEmpty()) {
            identity.setLastName(lastName);
        }
    }
}
