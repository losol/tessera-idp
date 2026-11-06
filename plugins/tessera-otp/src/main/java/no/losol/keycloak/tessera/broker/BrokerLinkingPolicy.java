// SPDX-License-Identifier: MPL-2.0
package no.losol.keycloak.tessera.broker;

/** Provider-independent policy. Existing account identity/attributes are never rewritten. */
public final class BrokerLinkingPolicy {
    public static final String SECURITY = "tessera.broker.security";
    public enum Decision { CREATE, AUTO_LINK, VERIFY_EMAIL, REJECT }
    private BrokerLinkingPolicy() { }

    /** Missing means standard; unknown settings fail closed, including typos. */
    public static boolean strict(String... settings) {
        for (String setting : settings) {
            if (setting != null && !"standard".equals(setting)) return true;
        }
        return false;
    }

    public static Decision decide(ValidatedBrokerIdentity identity, int matches,
            boolean previouslyVerified, boolean protectedAccount, boolean conflict, boolean strict) {
        if (strict || protectedAccount || conflict || matches > 1 || matches < 0
                || identity.email() == null || identity.email().isBlank()) return Decision.REJECT;
        // Email proof cannot silently attach an unverified pre-existing account.
        if (matches == 1 && !previouslyVerified) return Decision.REJECT;
        if (!identity.trustsEmail()) return Decision.VERIFY_EMAIL;
        return matches == 0 ? Decision.CREATE : Decision.AUTO_LINK;
    }
}
