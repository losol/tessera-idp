// tessera-otp · passwordless one-time-code authenticator for Keycloak
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

package no.losol.keycloak.tessera;

import no.losol.keycloak.tessera.ReviewAccess.Decision;
import no.losol.keycloak.tessera.ReviewAccess.Result;
import org.junit.jupiter.api.Test;
import org.keycloak.provider.ProviderConfigProperty;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReviewAccessTest {

    private static final String EMAIL = "appreview@example.org";
    private static final String CODE = "48215093";
    private static final String CLIENT = "civitas-mobile";
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    private static Map<String, String> config() {
        Map<String, String> config = new HashMap<>();
        config.put(ReviewAccess.CONFIG_ACCOUNTS, EMAIL + "=" + CODE);
        config.put(ReviewAccess.CONFIG_CLIENTS, CLIENT);
        config.put(ReviewAccess.CONFIG_VALID_UNTIL, "2026-12-01");
        return config;
    }

    private static Result resolve(Map<String, String> config, String email, String clientId) {
        return ReviewAccess.resolve(config, email, clientId, true, NOW);
    }

    @Test
    void grantsTheCodeWhenEverythingIsInOrder() {
        Result result = resolve(config(), EMAIL, CLIENT);
        assertTrue(result.granted());
        assertEquals(CODE, result.code());
    }

    @Test
    void isOffWithoutAllThreeSettings() {
        assertEquals(Decision.OFF, resolve(null, EMAIL, CLIENT).decision());
        assertEquals(Decision.OFF, resolve(Map.of(), EMAIL, CLIENT).decision());
        for (String key : List.of(ReviewAccess.CONFIG_ACCOUNTS, ReviewAccess.CONFIG_CLIENTS, ReviewAccess.CONFIG_VALID_UNTIL)) {
            Map<String, String> missing = config();
            missing.remove(key);
            assertEquals(Decision.OFF, resolve(missing, EMAIL, CLIENT).decision(), "missing " + key);
            missing.put(key, "   ");
            assertEquals(Decision.OFF, resolve(missing, EMAIL, CLIENT).decision(), "blank " + key);
        }
    }

    @Test
    void otherAddressesAreNotReviewAccounts() {
        for (String other : List.of("someone@example.org", "appreview+x@example.org", "appreview@sub.example.org", "xappreview@example.org")) {
            assertEquals(Decision.NOT_A_REVIEW_ACCOUNT, resolve(config(), other, CLIENT).decision(), other);
        }
    }

    @Test
    void stopsAtValidUntil() {
        Map<String, String> config = config();
        // A date covers the whole day in UTC.
        assertTrue(ReviewAccess.resolve(config, EMAIL, CLIENT, true, Instant.parse("2026-12-01T23:59:59Z")).granted());
        assertEquals(Decision.EXPIRED, ReviewAccess.resolve(config, EMAIL, CLIENT, true, Instant.parse("2026-12-02T00:00:00Z")).decision());

        config.put(ReviewAccess.CONFIG_VALID_UNTIL, "2026-11-20T10:00:00Z");
        assertEquals(Decision.EXPIRED, resolve(config, EMAIL, CLIENT).decision());
        config.put(ReviewAccess.CONFIG_VALID_UNTIL, " 2026-11-20T10:00:01Z ");
        assertTrue(resolve(config, EMAIL, CLIENT).granted());

        // Not a date at all: the feature is off rather than open-ended.
        config.put(ReviewAccess.CONFIG_VALID_UNTIL, "never");
        assertEquals(Decision.OFF, resolve(config, EMAIL, CLIENT).decision());
        config.put(ReviewAccess.CONFIG_VALID_UNTIL, "01.12.2026");
        assertEquals(Decision.OFF, resolve(config, EMAIL, CLIENT).decision());
    }

    @Test
    void onlyTheListedClientsQualify() {
        Map<String, String> config = config();
        assertEquals(Decision.CLIENT_NOT_ALLOWED, resolve(config, EMAIL, "account-console").decision());
        assertEquals(Decision.CLIENT_NOT_ALLOWED, resolve(config, EMAIL, "Civitas-Mobile").decision());
        assertEquals(Decision.CLIENT_NOT_ALLOWED, resolve(config, EMAIL, "civitas-mobile-web").decision());
        assertEquals(Decision.CLIENT_NOT_ALLOWED, resolve(config, EMAIL, null).decision());

        config.put(ReviewAccess.CONFIG_CLIENTS, "civitas-web, civitas-mobile\nthird");
        assertTrue(resolve(config, EMAIL, CLIENT).granted());
        assertTrue(resolve(config, EMAIL, "third").granted());
        config.put(ReviewAccess.CONFIG_CLIENTS, "civitas-*");
        assertEquals(Decision.CLIENT_NOT_ALLOWED, resolve(config, EMAIL, CLIENT).decision());
    }

    @Test
    void needsRealmBruteForceProtection() {
        assertEquals(Decision.REALM_NOT_PROTECTED, ReviewAccess.resolve(config(), EMAIL, CLIENT, false, NOW).decision());
    }

    @Test
    void parsesSeveralEntriesCaseInsensitively() {
        Map<String, String> accounts = ReviewAccess.parseAccounts(
                " Review@Example.org=01234567,other@example.org=987654321\nthird@example.org=12345678\n\n");
        assertEquals(3, accounts.size());
        assertEquals("01234567", accounts.get("review@example.org"), "leading zeroes are part of the code");
        assertEquals("987654321", accounts.get("other@example.org"));
        // The local part may contain '=': the split is at the last one.
        assertEquals("12345678", ReviewAccess.parseAccounts("a=b@example.org=12345678").get("a=b@example.org"));
    }

    @Test
    void oneBadEntryDisablesTheWholeList() {
        for (String bad : List.of(
                "review@example.org=1234567",          // 7 digits
                "review@example.org=abcdefgh",         // letters
                "review@example.org=1234 5678",        // whitespace inside
                "review@example.org=１２３４５６７８",   // not ASCII digits
                "*@example.org=12345678",              // wildcard
                "review?@example.org=12345678",
                "not-an-email=12345678",
                "review@example.org",                  // no code
                "review@example.org=12345678,REVIEW@EXAMPLE.ORG=87654321")) {
            assertTrue(ReviewAccess.parseAccounts("valid@example.org=12345678," + bad).isEmpty(), bad);
        }
    }

    @Test
    void theAccountsSettingIsASecretWithABlankDefault() {
        var properties = new TesseraOtpAuthenticatorFactory().getConfigProperties();
        var accounts = properties.stream().filter(p -> p.getName().equals(ReviewAccess.CONFIG_ACCOUNTS)).findFirst().orElseThrow();
        assertEquals(ProviderConfigProperty.PASSWORD, accounts.getType());
        assertEquals("", accounts.getDefaultValue());
        for (String key : List.of(ReviewAccess.CONFIG_CLIENTS, ReviewAccess.CONFIG_VALID_UNTIL)) {
            assertEquals("", properties.stream().filter(p -> p.getName().equals(key)).findFirst().orElseThrow().getDefaultValue());
        }
    }
}
