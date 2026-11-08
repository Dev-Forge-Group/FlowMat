package org.myweb.flowmat.domain.workflow.api.dto.request;

public record WorkflowUpdateRequest(
    String workflowName,
    String workflowDesc,
    String workflowType,
    String workflowStatus
) {
}
