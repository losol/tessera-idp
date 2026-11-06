// SPDX-License-Identifier: MPL-2.0
package no.losol.keycloak.tessera.broker;

import org.keycloak.authentication.Authenticator;
import org.keycloak.models.KeycloakSession;

public final class TesseraBrokerPolicyFactory extends TesseraBrokerLinkingFactory {
    @Override public String getId() { return "tessera-broker-policy"; }
    @Override public String getDisplayType() { return "Tessera broker security guard"; }
    @Override public Authenticator create(KeycloakSession session) { return new TesseraBrokerPolicy(); }
    @Override public String getHelpText() { return "Post-broker identity and stronger-security guard for every login."; }
}
