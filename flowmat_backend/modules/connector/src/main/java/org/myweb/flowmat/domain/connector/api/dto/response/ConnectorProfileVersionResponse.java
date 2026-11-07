package org.myweb.flowmat.domain.connector.api.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import org.myweb.flowmat.domain.connector.api.dto.request.ConnectorProfileRequest.Operation;

public record ConnectorProfileVersionResponse(String profileVersionId, String profileId, int revision, String baseOrigin,
                                              String credentialAlias, List<Operation> operations, String createdBy,
                                              OffsetDateTime createdAt) {
    public ConnectorProfileVersionResponse { operations = List.copyOf(operations); }
    @Override
    public String toString() { return "ConnectorProfileVersionResponse[profileId=" + profileId + ", revision=" + revision + "]"; }
}
