package org.myweb.flowmat.domain.production.api.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A run's instruction checklist (docs/domain/work-instruction.md): the revision the run works to (fixed at its first
 * confirmation, before that the product's released one; null when there is none) and the confirmations so far.
 *
 * @param open whether the run can still be confirmed (pending or running)
 * @param complete every required step is confirmed (true when there is no instruction)
 */
public record RunInstructionResponse(
    String productionRunId,
    boolean open,
    WorkInstructionResponse instruction,
    List<Check> checks,
    int requiredSteps,
    int requiredDone,
    boolean complete,
    /** Confirmations that were undone, the earliest undo first (docs/domain/work-instruction.md R6). */
    List<Undone> undone
) {

    public record Undone(
        String stepId,
        int stepNo,
        String value,
        String note,
        String checkedBy,
        OffsetDateTime checkedAt,
        String undoneBy,
        OffsetDateTime undoneAt
    ) {
    }

    public record Check(
        String stepId,
        String value,
        String note,
        String checkedBy,
        OffsetDateTime checkedAt,
        /** The value lies outside the step's limits (R7); it is recorded all the same. */
        boolean outOfLimits,
        /** The quality inspection the confirmation also recorded (R9); null when the step does not follow a standard. */
        String inspectionId,
        /** pass or fail, by the standard's limits when it was recorded. */
        String inspectionResult
    ) {
    }
}
