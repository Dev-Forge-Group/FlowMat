package org.myweb.flowmat.domain.workflow.application.publicapi;

/**
 * Workflow access checks for other bounded contexts (docs/architecture/adr/ADR-012-workflow-access-boundary.md): 404 when the
 * workflow is gone, then the project's read or write check, as before. Only ids come back.
 */
public interface WorkflowAccess {

    WorkflowAccessView requireRead(String workflowId);

    WorkflowAccessView requireRead(String workflowId, String userId);

    /**
     * The write check, then the workflow row's write lock until the caller's transaction ends. Revision retire takes the same
     * lock, so a run that starts sees the committed revision status.
     */
    WorkflowAccessView requireWriteAndLock(String workflowId);

    WorkflowAccessView requireWrite(String workflowId, String userId);
}
