package org.myweb.flowmat.domain.production.api.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One process step of a production run (docs/architecture/adr/ADR-011-production-steps.md). {@code instructionRequired} and
 * {@code instructionDone} count the process's required work instruction steps and those confirmed; both 0 without any.
 * {@code timeoutAt} is when the step passes its process's time limit; past it the step is only marked late
 * ({@code overdueAt}) and can still complete (docs/domain/flow-run.md F12). {@code held}: when its delay ended and whose
 * working time it waits for, the work order's equipment by its code and its crew (docs/architecture/adr/ADR-015-shift-aware-delay.md,
 * docs/architecture/adr/ADR-016-crew-aware-delay.md); null when the step was not held.
 */
public record ProductionRunStepResponse(
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
    int instructionRequired,
    int instructionDone,
    Held held
) {

    /** When the delay ended; the equipment (null without one) and the crew whose working time the step waits for. */
    public record Held(OffsetDateTime readyAt, String equipmentId, String equipmentCode, List<String> crew) {
    }
}
