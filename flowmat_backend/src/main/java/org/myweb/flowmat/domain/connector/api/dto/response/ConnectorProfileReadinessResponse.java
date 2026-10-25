package org.myweb.flowmat.domain.connector.api.dto.response;

import java.util.List;

/** Static configuration only. Neither credential values nor the destination connection have been tested. */
public record ConnectorProfileReadinessResponse(String profileVersionId, boolean configurationReady,
    List<Blocker> blockers, boolean credentialValuesChecked, boolean connectionTested) {
    public ConnectorProfileReadinessResponse {
        blockers = List.copyOf(blockers);
        configurationReady = blockers.isEmpty();
        credentialValuesChecked = false;
        connectionTested = false;
    }

    public ConnectorProfileReadinessResponse(String profileVersionId, List<Blocker> blockers) {
        this(profileVersionId, false, blockers, false, false);
    }

    public enum Blocker {
        CONNECTOR_DISABLED,
        PROFILE_RETIRED,
        PROFILE_DISABLED,
        TARGET_POLICY_REJECTED,
        CREDENTIAL_CONFIGURATION_UNAVAILABLE
    }

    @Override
    public String toString() {
        return "ConnectorProfileReadinessResponse[profileVersionId=" + profileVersionId
            + ", configurationReady=" + configurationReady + ", blockers=" + blockers + "]";
    }
}
