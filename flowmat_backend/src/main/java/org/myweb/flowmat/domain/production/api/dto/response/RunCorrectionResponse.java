package org.myweb.flowmat.domain.production.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record RunCorrectionResponse(
    String productionRunCorrectionId,
    String productionRunId,
    int correctionNo,
    /** pending_approval, applied or rejected. */
    String status,
    String reason,
    String requestedBy,
    OffsetDateTime requestedAt,
    String decidedBy,
    OffsetDateTime decidedAt,
    String decisionNote,
    OffsetDateTime appliedAt,
    List<Line> lines
) {

    public record Line(
        int lineNo,
        /** void_item, add_item, set_output_qty, cancel_setup, add_setup, cancel_running_time, add_running_time, cancel_labor or add_labor. */
        String kind,
        String targetRunItemId,
        String direction,
        String itemId,
        String inventoryId,
        BigDecimal qty,
        String unit,
        BigDecimal beforeQty,
        BigDecimal afterQty,
        String createdRunItemId,
        String targetRunSetupId,
        String equipmentId,
        Integer setupMinutes,
        String createdRunSetupId,
        String targetRunEquipmentTimeId,
        Integer runningMinutes,
        String createdRunEquipmentTimeId,
        /** cancel_labor: the worked time it cancels; add_labor: who worked, their minutes and the worked time it made. */
        String targetRunLaborId,
        String userId,
        Integer workedMinutes,
        String createdRunLaborId,
        /** add_labor: the process step it names; null for the whole run (LB17). */
        String stepId
    ) {
    }
}
