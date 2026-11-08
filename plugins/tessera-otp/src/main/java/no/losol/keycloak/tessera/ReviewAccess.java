// tessera-otp · passwordless one-time-code authenticator for Keycloak
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

package no.losol.keycloak.tessera;

import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Temporary fixed login codes for prepared accounts, e.g. the account an app
 * store reviewer uses to look at the app. The reviewer has no access to the
 * account's mailbox, so for these addresses the email step sends nothing and
 * the code step accepts the configured code instead.
 *
 * Three authenticator config keys, all required for the feature to be on:
 * <ul>
 *   <li>{@link #CONFIG_ACCOUNTS}: {@code email=code} entries, separated by
 *       commas or newlines. Codes are at least {@link #MIN_CODE_LENGTH} digits.</li>
 *   <li>{@link #CONFIG_CLIENTS}: the client ids the codes work for. Only these
 *       clients, so the review account cannot reach anything else this way.</li>
 *   <li>{@link #CONFIG_VALID_UNTIL}: an ISO-8601 date ({@code 2026-12-01}, the
 *       whole day in UTC) or instant ({@code 2026-12-01T12:00:00Z}). After it
 *       the codes stop working without anyone having to remember to remove them.</li>
 * </ul>
 * Anything missing or malformed switches the feature off, and the address
 * then goes through the ordinary emailed code. The realm must also have brute
 * force protection on, so wrong guesses against a fixed code lock the account
 * the way wrong passwords would.
 *
 * Decisions are pure functions of the config, so they are easy to test; the
 * authenticator only adds the Keycloak lookups. Config values never reach the
 * logs: the warnings name the key, not its contents.
 */
final class ReviewAccess {

    static final String CONFIG_ACCOUNTS = "review-accounts";
    static final String CONFIG_CLIENTS = "review-client-ids";
    static final String CONFIG_VALID_UNTIL = "review-valid-until";

    static final int MIN_CODE_LENGTH = 8;

    private static final Logger LOG = Logger.getLogger(ReviewAccess.class);

    // Same shape check as the email form, minus wildcard characters: a review
    // address is one exact address, never a pattern.
    private static final Pattern ADDRESS = Pattern.compile("^[^@\\s*?]+@[^@\\s*?]+\\.[^@\\s*?]+$");
    private static final Pattern CODE = Pattern.compile("[0-9]{" + MIN_CODE_LENGTH + ",}");

    // A broken list is worth one warning, not one per login attempt.
    private static final AtomicReference<String> WARNED_FOR = new AtomicReference<>();

    private ReviewAccess() {}

    /** Why the fixed code was not used; {@link #OK} when it was. */
    enum Decision { OK, OFF, NOT_A_REVIEW_ACCOUNT, EXPIRED, CLIENT_NOT_ALLOWED, REALM_NOT_PROTECTED }

    record Result(Decision decision, String code) {
        static final Result NOT_A_REVIEW_ACCOUNT = new Result(Decision.NOT_A_REVIEW_ACCOUNT, null);

        boolean granted() {
            return decision == Decision.OK;
        }
    }

    /**
     * The fixed code for {@code email} (already trimmed and lower-cased), or
     * why there is none. {@code config} may be null when the execution has no
     * config at all.
     */
    static Result resolve(Map<String, String> config, String email, String clientId,
                          boolean realmBruteForceProtected, Instant now) {
        if (config == null) return new Result(Decision.OFF, null);

        Map<String, String> accounts = parseAccounts(config.get(CONFIG_ACCOUNTS));
        Instant validUntil = parseValidUntil(config.get(CONFIG_VALID_UNTIL));
        String clients = config.get(CONFIG_CLIENTS);
        if (accounts.isEmpty() || validUntil == null || clients == null || clients.isBlank()) {
            return new Result(Decision.OFF, null);
        }

        String code = accounts.get(email);
        if (code == null) return Result.NOT_A_REVIEW_ACCOUNT;
        if (!now.isBefore(validUntil)) return new Result(Decision.EXPIRED, null);
        if (!allowsClient(clients, clientId)) return new Result(Decision.CLIENT_NOT_ALLOWED, null);
        if (!realmBruteForceProtected) return new Result(Decision.REALM_NOT_PROTECTED, null);
        return new Result(Decision.OK, code);
    }

    /**
     * Parses {@code email=code} entries. One bad entry disables the whole list,
     * so a typo never silently drops one account while the others keep working.
     */
    static Map<String, String> parseAccounts(String value) {
        Map<String, String> accounts = new HashMap<>();
        if (value == null || value.isBlank()) return accounts;
        for (String entry : value.split("[,\\r\\n]")) {
            if (entry.isBlank()) continue;
            // Split at the last '=': an address's local part may itself contain one.
            int separator = entry.lastIndexOf('=');
            if (separator < 0) return invalid(value, "an entry has no '='");
            String email = entry.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String code = entry.substring(separator + 1).trim();
            if (!ADDRESS.matcher(email).matches()) return invalid(value, "an entry is not a plain email address");
            if (!CODE.matcher(code).matches()) {
                return invalid(value, "a code is not at least " + MIN_CODE_LENGTH + " ASCII digits");
            }
            if (accounts.putIfAbsent(email, code) != null) return invalid(value, "an address is listed twice");
        }
        return accounts;
    }

    private static Map<String, String> invalid(String value, String reason) {
        if (!value.equals(WARNED_FOR.getAndSet(value))) {
            LOG.warnf("Ignoring %s: %s. Fixed review codes are off until it is fixed.", CONFIG_ACCOUNTS, reason);
        }
        return Map.of();
    }

    /** A date means the end of that day in UTC; an instant is used as is. Null when missing or malformed. */
    static Instant parseValidUntil(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException notAnInstant) {
            try {
                return LocalDate.parse(trimmed).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException notADate) {
                if (!trimmed.equals(WARNED_FOR.getAndSet(trimmed))) {
                    LOG.warnf("Ignoring %s: not an ISO-8601 date or instant. Fixed review codes are off until it is fixed.",
                            CONFIG_VALID_UNTIL);
                }
                return null;
            }
        }
    }

    /** Exact, case-sensitive client id match against a comma/whitespace-separated list. */
    static boolean allowsClient(String configuredClients, String clientId) {
        if (clientId == null) return false;
        for (String candidate : configuredClients.split("[,\\s]+")) {
            if (candidate.equals(clientId)) return true;
        }
        return false;
    }
}
