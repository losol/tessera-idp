// SPDX-License-Identifier: MPL-2.0
package no.losol.keycloak.tessera.broker;

import java.util.List;
import org.keycloak.broker.provider.AbstractIdentityProviderMapper;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.models.IdentityProviderMapperModel;
import org.keycloak.models.IdentityProviderSyncMode;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.provider.ProviderConfigProperty;

/** Attach to each provider: enforce invariants BEFORE Keycloak can update/link users. */
public final class TesseraBrokerClaimsMapper extends AbstractIdentityProviderMapper {
    @Override public String getId() { return "tessera-broker-claims"; }
    @Override public String[] getCompatibleProviders() { return new String[]{"google", "tessera-apple"}; }
    @Override public String getDisplayCategory() { return "Tessera"; }
    @Override public String getDisplayType() { return "Validated broker identity guard"; }
    @Override public String getHelpText() { return "Rejects unsafe broker configuration and inconsistent validated identity before account mutation."; }
    @Override public List<ProviderConfigProperty> getConfigProperties() { return List.of(); }
    @Override public boolean supportsSyncMode(IdentityProviderSyncMode mode) { return mode == IdentityProviderSyncMode.IMPORT; }
    @Override public void preprocessFederatedIdentity(KeycloakSession session, RealmModel realm,
            IdentityProviderMapperModel mapper, BrokeredIdentityContext context) {
        BrokerPolicySupport.configuration(context);
        ValidatedBrokerIdentity.from(context, false);
    }
}
