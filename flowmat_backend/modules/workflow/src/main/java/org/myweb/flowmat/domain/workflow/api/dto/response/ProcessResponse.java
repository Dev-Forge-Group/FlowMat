package org.myweb.flowmat.domain.workflow.api.dto.response;

public record ProcessResponse(
    String processId,
    String projectId,
    String workflowId,
    String processName,
    String processType,
    String nodeType,
    String processStatus,
    String colorScheme,
    Double posX,
    Double posY,
    Double width,
    Double height,
    String processDesc,
    int version,
    int versionNonce
) {
}
