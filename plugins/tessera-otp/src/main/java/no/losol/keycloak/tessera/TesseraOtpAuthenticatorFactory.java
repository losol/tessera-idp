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
