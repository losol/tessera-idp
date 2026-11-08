// tessera-otp · passwordless one-time-code authenticator for Keycloak
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

package no.losol.keycloak.tessera;

import org.junit.jupiter.api.Test;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReviewAccountsTest {
    @Test
    void disabledByDefaultAndMatchesExactAddressesIgnoringCase() {
        assertTrue(ReviewAccounts.parse(null).isEmpty());
        assertTrue(ReviewAccounts.parse(" \n ").isEmpty());
        Map<String, String> accounts = ReviewAccounts.parse(
                " Review@Example.org=01234567,other@example.org=987654321\nthird@example.org=12345678\n");
        assertEquals("01234567", accounts.get("review@example.org"));
        assertEquals(3, accounts.size());
        assertNull(accounts.get("review+other@example.org"));
        assertNull(accounts.get("prefixreview@example.org"));
        assertNull(accounts.get("review@sub.example.org"));
        assertEquals("12345678", ReviewAccounts.parse("review=ios@example.org=12345678").get("review=ios@example.org"));
    }

    @Test
    void invalidOrDuplicateEntriesDisableTheEntireList() {
        for (String invalid : new String[]{"review@example.org=1234567", "review@example.org=abcdefgh",
                "review@example.org=１２３４５６７８", "*@example.org=12345678", "review?@example.org=12345678",
                "not-an-email=12345678", "review@example.org", "review@example.org=1234 5678",
                "review@example.org=12345678,REVIEW@EXAMPLE.ORG=87654321"}) {
            assertTrue(ReviewAccounts.parse("valid@example.org=12345678," + invalid).isEmpty());
        }
    }

    @Test
    void clientIdsAreExactAndCaseSensitive() {
        assertTrue(ReviewAccounts.allowsClient("", null));
        assertTrue(ReviewAccounts.allowsClient("civitas-mobile,other\nthird", "civitas-mobile"));
        assertFalse(ReviewAccounts.allowsClient("civitas-mobile", "Civitas-mobile"));
        assertFalse(ReviewAccounts.allowsClient("civitas-mobile", "civitas-mobile-web"));
        assertFalse(ReviewAccounts.allowsClient("civitas-*", "civitas-mobile"));
        assertFalse(ReviewAccounts.allowsClient("civitas-mobile", null));
    }

    @Test
    void factoryDeclaresSecretWithBlankDefault() {
        var property = new TesseraOtpAuthenticatorFactory().getConfigProperties().stream()
                .filter(p -> p.getName().equals(ReviewAccounts.ACCOUNTS)).findFirst().orElseThrow();
        assertEquals(ProviderConfigProperty.PASSWORD, property.getType());
        assertEquals("", property.getDefaultValue());
    }
}
