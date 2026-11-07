package org.myweb.flowmat.domain.workflow.application.publicapi;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/**
 * Workflows, processes and ports for production runs, which read them here instead of the workflow repositories and
 * entities (docs/architecture/adr/ADR-018-entity-boundary.md). The caller checks project access.
 */
public interface WorkflowRunQuery {

    /** The workflow if it exists and is not deleted, whatever its project. */
    Optional<WorkflowRunView> findWorkflow(String workflowId);

    /**
     * The workflow locked inside the caller's transaction (which must exist), as publishing and retiring lock it, and read after
     * the lock; 404 when it does not exist or is deleted.
     */
    WorkflowRunView lockWorkflow(String workflowId);

    /** The process if it exists and is not deleted. */
    Optional<ProcessRunView> findProcess(String processId);

    /** The port if it exists and is not deleted. */
    Optional<ProcessIoRunView> findProcessIo(String processIoId);

    /**
     * The process with this id as a published revision's snapshot ({@link WorkflowProductionQuery.Revision#snapshotJson()})
     * keeps it; empty when the snapshot has none. A snapshot or entry that cannot be read is an IllegalStateException.
     */
    Optional<ProcessRunView> revisionProcess(String snapshotJson, String processId);

    /** The port with this id as a published revision's snapshot keeps it; empty when the snapshot has none. */
    Optional<ProcessIoRunView> revisionProcessIo(String snapshotJson, String processIoId);

    /** A process as a published revision's snapshot keeps it, from an entry of the snapshot's processes. */
    ProcessRunView snapshotProcess(JsonNode entry);

    /** A port as a published revision's snapshot keeps it, from an entry of the snapshot's processIos. */
    ProcessIoRunView snapshotProcessIo(JsonNode entry);
}
