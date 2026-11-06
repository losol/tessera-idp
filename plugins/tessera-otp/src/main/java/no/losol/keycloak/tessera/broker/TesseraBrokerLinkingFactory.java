// SPDX-License-Identifier: MPL-2.0
package no.losol.keycloak.tessera.broker;

import java.util.List;
import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

public class TesseraBrokerLinkingFactory implements AuthenticatorFactory {
    @Override public String getId() { return "tessera-broker-linking"; }
    @Override public String getDisplayType() { return "Tessera verified broker linking"; }
    @Override public Authenticator create(KeycloakSession session) { return new TesseraBrokerLinking(); }
    @Override public String getReferenceCategory() { return "tessera-broker"; }
    @Override public boolean isConfigurable() { return false; }
    @Override public Requirement[] getRequirementChoices() { return new Requirement[]{Requirement.REQUIRED}; }
    @Override public boolean isUserSetupAllowed() { return false; }
    @Override public String getHelpText() { return "First broker attachment using Tessera's verified email policy."; }
    @Override public List<ProviderConfigProperty> getConfigProperties() { return List.of(); }
    @Override public void init(Config.Scope config) { }
    @Override public void postInit(KeycloakSessionFactory factory) { }
    @Override public void close() { }
}
