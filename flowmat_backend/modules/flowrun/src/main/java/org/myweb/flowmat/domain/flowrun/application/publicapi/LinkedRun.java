package org.myweb.flowmat.domain.flowrun.application.publicapi;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A flow run kept alongside another domain's run; today that is a production run, linked by {@code productionRunId}.
 * {@code input} becomes the run's input payload and the payload of its start event; {@code requestedBy} is who started it.
 */
public record LinkedRun(
    String projectId,
    String workflowId,
    String workflowRevisionId,
    String productionRunId,
    String runType,
    String requestedBy,
    ObjectNode input
) {
}
