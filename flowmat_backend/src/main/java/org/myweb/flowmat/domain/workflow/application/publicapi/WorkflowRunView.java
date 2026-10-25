package org.myweb.flowmat.domain.workflow.application.publicapi;

import java.util.Map;

/**
 * A workflow as a production run reads it: its project, and in {@code ruleFacts} every property the workflow keeps, read-only,
 * for flow rules (docs/architecture/adr/ADR-018-entity-boundary.md decision 5).
 */
public record WorkflowRunView(String workflowId, String projectId, Map<String, Object> ruleFacts) {
}
