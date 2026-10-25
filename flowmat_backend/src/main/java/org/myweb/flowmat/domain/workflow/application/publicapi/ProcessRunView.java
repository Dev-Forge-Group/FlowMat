package org.myweb.flowmat.domain.workflow.application.publicapi;

import java.util.Map;

/**
 * A process as production reads it: its project, workflow and name, and in {@code ruleFacts} every property the process
 * keeps, read-only, for flow rules (docs/architecture/adr/ADR-018-entity-boundary.md decision 5).
 */
public record ProcessRunView(String processId, String projectId, String workflowId, String processName, Map<String, Object> ruleFacts) {
}
