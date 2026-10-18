package org.myweb.flowmat.domain.flowrun.application.publicapi;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

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
}
