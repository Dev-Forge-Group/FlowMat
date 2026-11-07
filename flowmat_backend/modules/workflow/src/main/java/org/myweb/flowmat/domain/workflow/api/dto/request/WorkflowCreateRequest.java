package org.myweb.flowmat.domain.workflow.api.dto.request;

import jakarta.validation.constraints.NotBlank;

public record WorkflowCreateRequest(
    @NotBlank String projectId,
    @NotBlank String workflowName,
    String workflowDesc,
    String workflowType
) {
}
