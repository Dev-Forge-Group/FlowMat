package org.myweb.flowmat.domain.flowrun.application.publicapi;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One process step of a production run's linked flow run (docs/architecture/adr/ADR-011-production-steps.md).
 * {@code nodeName} is the process name in the run's revision; {@code gateway} is true for a step the server passes itself
 * (Exclusive, Join, Split); {@code joinWaiting} is how many branches a waiting Join still waits for, else null.
 * {@code timeoutAt} is when the latest attempt passes its process's time limit, null without one; {@code overdueAt} is when a
 * running step was marked late for passing it, as it keeps running (docs/domain/flow-run.md F12). {@code held} is set when
 * a connection's delay ended outside the time the work order's equipment and crew work, so the step was held to the next
 * time they do (docs/architecture/adr/ADR-015-shift-aware-delay.md, docs/architecture/adr/ADR-016-crew-aware-delay.md).
 */
public record LinkedRunStep(
    String stepId,
    String nodeId,
    String nodeName,
    Integer sequenceNo,
    String status,
    boolean gateway,
    String sourceStepId,
    String sourceConnectionId,
    OffsetDateTime scheduledAt,
    OffsetDateTime startedAt,
    OffsetDateTime endedAt,
    String errorCode,
    String errorMessage,
    JsonNode inputSnapshot,
    JsonNode outputSnapshot,
    Integer joinWaiting,
    OffsetDateTime timeoutAt,
    OffsetDateTime overdueAt,
    Held held
) {

    /** When the delay ended and whose working time the step waits for: the equipment (null without one) and the crew. */
    public record Held(OffsetDateTime readyAt, String equipmentId, List<String> crew) {
    }
}
