// tessera · Sign in with Apple identity provider for Keycloak
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

package no.losol.keycloak.tessera.apple;

import org.junit.jupiter.api.Test;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.models.IdentityProviderModel;

import static org.junit.jupiter.api.Assertions.*;

class AppleUserTest {

    private static BrokeredIdentityContext identity() {
        IdentityProviderModel model = new IdentityProviderModel();
        model.setEnabled(true);
        return new BrokeredIdentityContext("001234.abcdef.1234", model);
    }

    @Test
    void takesTheNameFromAppleFirstConsentUserField() {
        BrokeredIdentityContext identity = identity();

        AppleUser.applyName(identity,
                "{\"name\":{\"firstName\":\"Kari\",\"lastName\":\"Nordmann\"},\"email\":\"kari@example.com\"}");

        assertEquals("Kari", identity.getFirstName());
        assertEquals("Nordmann", identity.getLastName());
        // The email in the user field is not trusted; only the ID token's is.
        assertNull(identity.getEmail());
    }

    @Test
    void leavesTheIdentityAloneWhenTheUserFieldHasNoUsableName() {
        for (String json : new String[]{"{}", "{\"name\":{}}", "{\"name\":{\"firstName\":\"  \"}}", "null", "", "not json"}) {
            BrokeredIdentityContext identity = identity();
            identity.setFirstName("Existing");

            AppleUser.applyName(identity, json);

            assertEquals("Existing", identity.getFirstName(), json);
            assertNull(identity.getLastName(), json);
        }
    }
}
