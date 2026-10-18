package org.myweb.flowmat.domain.project.application.publicapi;

/**
 * Changes to a project that follow from its workflows (docs/architecture/adr/ADR-012-workflow-access-boundary.md decision 3),
 * in the caller's transaction.
 */
public interface ProjectWorkflowCommand {

    /** Makes the workflow the project's current one when the project has none yet. */
    void workflowAdded(String projectId, String workflowId);

    /** Clears the project's current workflow when it is this one. */
    void workflowRemoved(String projectId, String workflowId);
}
