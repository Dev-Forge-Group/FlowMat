package org.myweb.flowmat.domain.workflow.api.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ProcessCreateRequest(
    @NotBlank String workflowId,
    @NotBlank String processName,
    String processType,
    String nodeType,
    String colorScheme,
    Double posX,
    Double posY,
    Double width,
    Double height,
    String processDesc
) {
}
