package org.myweb.flowmat.domain.workflow.application.publicapi;
import java.util.Optional;
/**
 * Published workflow revisions for production and flow runs; the caller checks project access. Workflows, processes and
 * ports themselves are read through WorkflowRunQuery (docs/architecture/adr/ADR-018-entity-boundary.md).
 */
public interface WorkflowProductionQuery {
    record Revision(String workflowRevisionId, String status, String snapshotJson, int revisionNo) { }
    Optional<Revision> findRevision(String id,String workflowId);
    Optional<Revision> latestPublishedRevision(String workflowId);
}
