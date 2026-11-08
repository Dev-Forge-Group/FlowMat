package org.myweb.flowmat.domain.workflow.collab;

public record WorkflowSessionRecord(
    String sessionId,
    String userId,
    String workflowId,
    String clientId,
    Double cursorX,
    Double cursorY,
    String editingProcessId,
    long lastSeenAt
) {
}
