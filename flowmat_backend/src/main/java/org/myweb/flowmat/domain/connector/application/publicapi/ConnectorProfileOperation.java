package org.myweb.flowmat.domain.connector.application.publicapi;

import java.util.Set;

/** Immutable approved operation metadata. An alias is a deployment lookup key, never a credential value. */
public record ConnectorProfileOperation(String projectId, String profileId, String profileVersionId, int revision,
                                        String baseOrigin, String credentialAlias, String operationId, String method,
                                        String relativePath, Set<Integer> successStatuses) {
    public ConnectorProfileOperation { successStatuses = Set.copyOf(successStatuses); }
    @Override
    public String toString() {
        return "ConnectorProfileOperation[profileId=" + profileId + ", revision=" + revision + ", operationId=" + operationId + "]";
    }
}
