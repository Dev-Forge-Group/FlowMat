package org.myweb.flowmat.domain.flowrun.application.publicapi;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;

/**
 * The process steps of production runs, for the production run API (docs/architecture/adr/ADR-011-production-steps.md
 * decision 2). The caller authorizes the production run and keeps it open; changes join its transaction. A run that has no
 * process steps (started before them, or with a revision that cannot run as a graph) lists none and refuses changes with 409.
 */
public interface LinkedRunSteps {

    /** The run's steps, earliest first; empty when it has none. */
    List<LinkedRunStep> steps(String productionRunId);

    LinkedRunStep start(String productionRunId, String stepId);

    /** Completes a running step with {@code output}; its connections then route as in a graph run. */
    LinkedRunStep complete(String productionRunId, String stepId, JsonNode output);

    LinkedRunStep fail(String productionRunId, String stepId, String errorCode, String errorMessage);

    /**
     * Why runs on this revision would start without process steps, as a revision that cannot run as a graph does (no
     * processes, a cycle); empty when they would have them or there is no such revision. For readiness before a run starts
     * (docs/domain/work-order-readiness.md).
     */
    Optional<String> stepsUnavailable(String workflowId, String workflowRevisionId);
}
