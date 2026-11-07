package org.myweb.flowmat.domain.workflow.api.dto.response;

public record WorkflowResponse(
    String workflowId,
    String projectId,
    String workflowName,
    String workflowDesc,
    String workflowType,
    String workflowStatus
) {
}
