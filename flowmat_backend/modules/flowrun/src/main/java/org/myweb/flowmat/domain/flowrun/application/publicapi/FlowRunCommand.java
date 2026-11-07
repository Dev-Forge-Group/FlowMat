package org.myweb.flowmat.domain.flowrun.application.publicapi;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Flow Run changes for other bounded contexts, which use this instead of the flow run repository
 * (docs/architecture/adr/ADR-002-module-dependency.md). Both operations join the caller's transaction, which must exist,
 * so the flow run commits or rolls back with the caller's own run. Add an operation only when a caller needs it.
 */
public interface FlowRunCommand {

    /** Starts a running flow run linked to {@code run}; 400 when the run type is not a flow run type. */
    void startLinkedRun(LinkedRun run);

    /**
     * Finishes the flow run linked to {@code run.productionRunId()} with {@code output}, starting it first if it never was.
     * A run with no linked flow run whose type is not a flow run type is left alone: production runs could use other types
     * before the shared run contract existed. 409 when the linked flow run has already ended.
     */
    void finishLinkedRun(LinkedRun run, ObjectNode output, String finishedBy);
}
