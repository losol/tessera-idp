// tessera-otp · passwordless one-time-code authenticator for Keycloak
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

package no.losol.keycloak.tessera;

import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.List;

public class TesseraOtpAuthenticatorFactory implements AuthenticatorFactory {

    public static final String PROVIDER_ID = "tessera-otp";

    private static final TesseraOtpAuthenticator INSTANCE = new TesseraOtpAuthenticator();

    private static final List<ProviderConfigProperty> CONFIG_PROPERTIES = List.of(
            new ProviderConfigProperty(
                    "code-length",
                    "Code Length",
                    "Number of characters in the OTP code",
                    ProviderConfigProperty.STRING_TYPE,
                    String.valueOf(TesseraOtpAuthenticator.DEFAULT_LENGTH)),
            new ProviderConfigProperty(
                    "code-lifetime",
                    "Code Lifetime (seconds)",
                    "How long the code is valid",
                    ProviderConfigProperty.STRING_TYPE,
                    String.valueOf(TesseraOtpAuthenticator.DEFAULT_LIFETIME_SECONDS)),
            new ProviderConfigProperty(
                    "code-alphabet",
                    "Code Alphabet",
                    "Characters used to generate the code",
                    ProviderConfigProperty.STRING_TYPE,
                    TesseraOtpAuthenticator.DEFAULT_ALPHABET),
            new ProviderConfigProperty(
                    "altcha-hmac-key",
                    "ALTCHA HMAC Key",
                    "HMAC secret enabling the ALTCHA proof-of-work captcha on the email step. "
                            + "Leave blank to disable the captcha entirely.",
                    ProviderConfigProperty.PASSWORD,
                    ""),
            new ProviderConfigProperty(
                    "altcha-cost",
                    "ALTCHA Cost",
                    "PBKDF2 iterations per ALTCHA proof-of-work attempt. Higher means more work "
                            + "for bots, and a slower solve on slow phones. Only used when the "
                            + "HMAC key is set.",
                    ProviderConfigProperty.STRING_TYPE,
                    String.valueOf(TesseraOtpAuthenticator.DEFAULT_ALTCHA_COST)),
            // Temporary fixed codes for prepared review accounts (ReviewAccess).
            // All three must be set for the feature to be on.
            new ProviderConfigProperty(
                    ReviewAccess.CONFIG_ACCOUNTS,
                    "Review Accounts",
                    "Fixed login codes for prepared review accounts, as email=code entries separated by "
                            + "commas or newlines. Codes need at least " + ReviewAccess.MIN_CODE_LENGTH
                            + " digits; the user must already exist. No email is sent to these addresses. "
                            + "Also needs Review Client IDs and Review Valid Until. Blank disables.",
                    ProviderConfigProperty.PASSWORD,
                    ""),
            new ProviderConfigProperty(
                    ReviewAccess.CONFIG_CLIENTS,
                    "Review Client IDs",
                    "The only client IDs the review codes work for (exact match, separated by commas "
                            + "or whitespace). Required for review codes; ordinary logins are unaffected.",
                    ProviderConfigProperty.STRING_TYPE,
                    ""),
            new ProviderConfigProperty(
                    ReviewAccess.CONFIG_VALID_UNTIL,
                    "Review Valid Until",
                    "When the review codes stop working: an ISO-8601 date (2026-12-01, the whole day "
                            + "in UTC) or instant (2026-12-01T12:00:00Z). Required for review codes.",
                    ProviderConfigProperty.STRING_TYPE,
                    "")
    );

    @Override
    public String getDisplayType() {
        return "Tessera OTP";
    }

    @Override
    public String getReferenceCategory() {
        return "otp";
    }

    @Override
    public boolean isConfigurable() {
        return true;
    }

    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        return new AuthenticationExecutionModel.Requirement[]{
                AuthenticationExecutionModel.Requirement.REQUIRED,
                AuthenticationExecutionModel.Requirement.ALTERNATIVE,
                AuthenticationExecutionModel.Requirement.DISABLED
        };
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public String getHelpText() {
        return "Sends a one-time code to the user's email address for passwordless authentication.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return CONFIG_PROPERTIES;
    }

    @Override
    public Authenticator create(KeycloakSession session) {
        return INSTANCE;
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }
}
