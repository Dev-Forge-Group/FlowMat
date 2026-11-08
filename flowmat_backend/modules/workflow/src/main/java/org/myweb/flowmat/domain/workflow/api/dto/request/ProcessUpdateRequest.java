package org.myweb.flowmat.domain.workflow.api.dto.request;

public record ProcessUpdateRequest(
    String processName,
    String processType,
    String nodeType,
    String processStatus,
    String colorScheme,
    Double posX,
    Double posY,
    Double width,
    Double height,
    String processDesc
) {
}
