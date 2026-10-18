package org.myweb.flowmat.domain.production.api.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

/** A work instruction revision with its steps in order (docs/domain/work-instruction.md). */
public record WorkInstructionResponse(
    String instructionId,
    String projectId,
    String itemId,
    String itemCode,
    String itemName,
    int revisionNo,
    String status,
    String title,
    String body,
    String documentUrl,
    String releasedBy,
    OffsetDateTime releasedAt,
    OffsetDateTime updatedAt,
    List<Step> steps,
    /** A run cannot finish until the required steps are confirmed. */
    boolean blocksFinish
) {

    public record Step(
        String stepId,
        int stepNo,
        String text,
        boolean required,
        boolean recordsValue,
        String valueLabel,
        /** Limits for the recorded value; null when there are none. */
        java.math.BigDecimal valueMin,
        java.math.BigDecimal valueMax,
        /** The inspection standard the limits come from (W13); null when they were typed. */
        String standardId,
        /** The standard's check, such as "Moisture". */
        String standardName,
        /**
         * current: the step's limits are the standard's; changed: the standard's limits changed since (a draft takes them
         * when released); inactive: the standard is no longer an active production standard. Null without a standard.
         */
        String standardStatus,
        /** The process the step belongs to and its name (ADR-011 decision 6); null for the whole run. */
        String processId,
        String processName
    ) {
    }
}
