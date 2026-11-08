package org.myweb.flowmat.domain.workflow.api.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ProcessTemplateApplyRequest(
    @NotBlank String workflowId,
    String processName,
    String processType,
    String nodeType,
    String colorScheme,
    Double posX,
    Double posY
) {
}
