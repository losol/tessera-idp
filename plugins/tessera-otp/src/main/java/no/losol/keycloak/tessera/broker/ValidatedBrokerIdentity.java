// SPDX-License-Identifier: MPL-2.0
package no.losol.keycloak.tessera.broker;

import java.util.Objects;
import org.keycloak.broker.oidc.OIDCIdentityProvider;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.representations.JsonWebToken;

/** Only constructed from the broker's validated token, never a form/profile mapper. */
public record ValidatedBrokerIdentity(String issuer, String subject, String email,
                                      boolean emailVerified, String hostedDomain) {
    public static final String GOOGLE_ISSUER = "https://accounts.google.com";
    public static final String APPLE_ISSUER = "https://appleid.apple.com";

    public static ValidatedBrokerIdentity from(BrokeredIdentityContext context, boolean firstAttachment) {
        String provider = context.getIdpConfig().getProviderId();
        String issuer = switch (provider) {
            case "google" -> GOOGLE_ISSUER;
            // Registered only by the hardened Apple provider supplied by issue #93.
            case "tessera-apple" -> APPLE_ISSUER;
            default -> throw new IdentityBrokerException("Unsupported Tessera broker");
        };
        Object value = context.getContextData().get(OIDCIdentityProvider.VALIDATED_ID_TOKEN);
        if (!(value instanceof JsonWebToken token)
                || !issuer.equals(token.getIssuer())
                || token.getSubject() == null || token.getSubject().isBlank()
                || !token.getSubject().equals(context.getId())) {
            throw new IdentityBrokerException("Missing or inconsistent validated broker identity");
        }
        String email = stringClaim(token, "email");
        if (firstAttachment && (email == null || email.isBlank()
                || !Objects.equals(email, context.getEmail()))) {
            throw new IdentityBrokerException("First attachment requires the validated token email");
        }
        Object verified = token.getOtherClaims().get("email_verified");
        // Google's claim is a JSON boolean. Apple's documented encoding may also be a string.
        boolean emailVerified = Boolean.TRUE.equals(verified)
                || (APPLE_ISSUER.equals(issuer) && "true".equals(verified));
        return new ValidatedBrokerIdentity(issuer, token.getSubject(), email,
                emailVerified, stringClaim(token, "hd"));
    }

    private static String stringClaim(JsonWebToken token, String name) {
        Object value = token.getOtherClaims().get(name);
        return value instanceof String text ? text : null;
    }

    public boolean trustsEmail() {
        if (!emailVerified || email == null || email.isBlank()) return false;
        if (APPLE_ISSUER.equals(issuer)) return true;
        if (!GOOGLE_ISSUER.equals(issuer)) return false;
        int at = email.lastIndexOf('@');
        return (at > 0 && "gmail.com".equalsIgnoreCase(email.substring(at + 1)))
                || (hostedDomain != null && !hostedDomain.isBlank());
    }
}
