package org.myweb.flowmat.domain.connector.api.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import org.myweb.flowmat.domain.connector.api.dto.request.ConnectorProfileRequest.Operation;

public record ConnectorProfileResponse(String profileId, String projectId, String name, boolean enabled, long version,
                                       int currentRevision, String profileVersionId, String baseOrigin, String credentialAlias,
                                       List<Operation> operations, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    public ConnectorProfileResponse { operations = List.copyOf(operations); }
    @Override
    public String toString() { return "ConnectorProfileResponse[profileId=" + profileId + ", version=" + version + "]"; }
}
