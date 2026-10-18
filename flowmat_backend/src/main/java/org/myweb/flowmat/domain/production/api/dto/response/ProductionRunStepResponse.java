package org.myweb.flowmat.domain.production.api.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

/**
 * One process step of a production run (docs/architecture/adr/ADR-011-production-steps.md). {@code instructionRequired} and
 * {@code instructionDone} count the process's required work instruction steps and those confirmed; both 0 without any.
 * {@code timeoutAt} is when the step passes its process's time limit; past it the step is only marked late
 * ({@code overdueAt}) and can still complete (docs/domain/flow-run.md F12).
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
    int instructionDone
) {
}
