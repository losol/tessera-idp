// tessera · Discord identity provider for Keycloak
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

package no.losol.keycloak.tessera.discord;

import com.fasterxml.jackson.databind.JsonNode;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;

/**
 * What the realm takes from Discord's {@code /users/@me} profile:
 * <pre>
 * {"id":"80351110224678912","username":"nelly","global_name":"Nelly",
 *  "email":"nelly@example.org","verified":true, ...}
 * </pre>
 * <ul>
 *   <li>{@code id} is the permanent identity (a snowflake); usernames change.</li>
 *   <li>{@code global_name} is the display name people choose; it is set as
 *       the name, which Keycloak splits into first and last. When it is
 *       missing the name stays empty and the profile step asks for it.</li>
 *   <li>{@code email} is set as given, but marked verified only when Discord's
 *       {@code verified} is true. The first-broker-login flow links to an
 *       existing account by email only on a verified address; otherwise it
 *       falls back to Keycloak's email link. Without the {@code email} scope
 *       there is no address at all, and the account is new unless linked
 *       by hand.</li>
 * </ul>
 */
final class DiscordUser {

    /** The context-data key Keycloak reads back as {@link BrokeredIdentityContext#isEmailVerified()}. */
    static final String EMAIL_VERIFIED = "email_verified";

    private DiscordUser() {
    }

    static String id(JsonNode profile) {
        String id = text(profile, "id");
        if (id == null) {
            throw new IdentityBrokerException("Discord profile has no id");
        }
        return id;
    }

    static void apply(BrokeredIdentityContext identity, JsonNode profile) {
        String username = text(profile, "username");
        if (username != null) {
            identity.setUsername(username);
        }
        String name = text(profile, "global_name");
        if (name != null) {
            identity.setName(name);
        }
        String email = text(profile, "email");
        if (email != null) {
            identity.setEmail(email);
            identity.getContextData().put(EMAIL_VERIFIED, profile.path("verified").asBoolean(false));
        }
    }

    private static String text(JsonNode profile, String field) {
        JsonNode node = profile.path(field);
        if (node.isNull() || node.isMissingNode()) return null;
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }
}
