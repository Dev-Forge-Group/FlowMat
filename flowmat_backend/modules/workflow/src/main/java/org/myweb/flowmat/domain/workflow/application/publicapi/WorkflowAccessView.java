package org.myweb.flowmat.domain.workflow.application.publicapi;

/** A workflow another bounded context may act on: its id and its project. */
public record WorkflowAccessView(String workflowId, String projectId) {
}
