// tessera · Discord identity provider for Keycloak
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

package no.losol.keycloak.tessera.discord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.models.IdentityProviderModel;

import static org.junit.jupiter.api.Assertions.*;

class DiscordUserTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode profile(String json) throws Exception {
        return JSON.readTree(json);
    }

    private static BrokeredIdentityContext identity(JsonNode profile) {
        IdentityProviderModel model = new IdentityProviderModel();
        model.setAlias("discord");
        model.setEnabled(true);
        BrokeredIdentityContext identity = new BrokeredIdentityContext(DiscordUser.id(profile), model);
        DiscordUser.apply(identity, profile);
        return identity;
    }

    @Test
    void takesIdUsernameDisplayNameAndVerifiedEmail() throws Exception {
        BrokeredIdentityContext identity = identity(profile(
                "{\"id\":\"80351110224678912\",\"username\":\"nelly\",\"discriminator\":\"0\","
                        + "\"global_name\":\"Nelly Nordmann\",\"avatar\":\"8342729096ea3675442027381ff50dfe\","
                        + "\"email\":\"Nelly@example.org\",\"verified\":true}"));

        assertEquals("80351110224678912", identity.getId());
        assertEquals("nelly", identity.getUsername());
        assertEquals("Nelly", identity.getFirstName());
        assertEquals("Nordmann", identity.getLastName());
        assertEquals("Nelly@example.org", identity.getEmail());
        assertTrue(identity.isEmailVerified());
    }

    @Test
    void anUnverifiedEmailIsPassedOnButNotTrusted() throws Exception {
        BrokeredIdentityContext identity = identity(profile(
                "{\"id\":\"1\",\"username\":\"nelly\",\"email\":\"nelly@example.org\",\"verified\":false}"));
        assertEquals("nelly@example.org", identity.getEmail());
        assertFalse(identity.isEmailVerified());

        // No `verified` field at all is not a verified address either.
        identity = identity(profile("{\"id\":\"1\",\"username\":\"nelly\",\"email\":\"nelly@example.org\"}"));
        assertFalse(identity.isEmailVerified());
    }

    @Test
    void withoutTheEmailScopeThereIsNoEmail() throws Exception {
        for (String json : new String[]{
                "{\"id\":\"1\",\"username\":\"nelly\"}",
                "{\"id\":\"1\",\"username\":\"nelly\",\"email\":null,\"verified\":true}",
                "{\"id\":\"1\",\"username\":\"nelly\",\"email\":\"  \"}"}) {
            BrokeredIdentityContext identity = identity(profile(json));
            assertNull(identity.getEmail(), json);
            assertFalse(identity.isEmailVerified(), json);
        }
    }

    @Test
    void aMissingDisplayNameLeavesTheNameEmpty() throws Exception {
        BrokeredIdentityContext identity = identity(profile("{\"id\":\"1\",\"username\":\"nelly\",\"global_name\":null}"));
        assertEquals("nelly", identity.getUsername());
        assertNull(identity.getFirstName());
        assertNull(identity.getLastName());
    }

    @Test
    void aProfileWithoutAnIdIsRejected() throws Exception {
        for (String json : new String[]{"{}", "{\"id\":null}", "{\"id\":\"\"}", "{\"username\":\"nelly\"}"}) {
            JsonNode profile = profile(json);
            assertThrows(IdentityBrokerException.class, () -> DiscordUser.id(profile), json);
        }
    }
}
