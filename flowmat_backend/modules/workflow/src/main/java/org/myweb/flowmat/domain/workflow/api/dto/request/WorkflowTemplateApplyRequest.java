package org.myweb.flowmat.domain.workflow.api.dto.request;

import jakarta.validation.constraints.NotBlank;

public record WorkflowTemplateApplyRequest(
    @NotBlank String projectId,
    String workflowName
) {
}
