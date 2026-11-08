// tessera-otp · passwordless one-time-code authenticator for Keycloak
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

package no.losol.keycloak.tessera;

import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Parses the secret without ever including its contents in diagnostics. */
final class ReviewAccounts {
    static final String ACCOUNTS = "review-accounts";
    static final String CLIENTS = "review-client-ids";
    private static final Logger LOG = Logger.getLogger(ReviewAccounts.class);
    private static final Pattern ADDRESS = Pattern.compile("^[^@\\s*?]+@[^@\\s*?]+\\.[^@\\s*?]+$");
    private static final Pattern CODE = Pattern.compile("[0-9]{8,}");

    private ReviewAccounts() {}

    static Map<String, String> parse(String value) {
        Map<String, String> accounts = new HashMap<>();
        if (value == null || value.isBlank()) return accounts;
        for (String entry : value.split("[,\\r\\n]", -1)) {
            if (entry.isBlank()) continue;
            // Split at the last '=': an exact email local part may contain '='.
            int separator = entry.lastIndexOf('=');
            String email = separator < 0 ? "" : entry.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String code = separator < 0 ? "" : entry.substring(separator + 1).trim();
            if (!ADDRESS.matcher(email).matches() || !CODE.matcher(code).matches()
                    || accounts.putIfAbsent(email, code) != null) {
                LOG.warn("Ignoring entire review-accounts configuration: invalid entry or duplicate address; codes must contain at least 8 ASCII digits");
                return Map.of();
            }
        }
        return accounts;
    }

    static boolean allowsClient(String configuredClients, String clientId) {
        if (configuredClients == null || configuredClients.isBlank()) return true;
        if (clientId == null) return false;
        for (String candidate : configuredClients.split("[,\\s]+")) {
            if (candidate.equals(clientId)) return true;
        }
        return false;
    }
}
