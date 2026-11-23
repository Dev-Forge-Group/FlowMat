package org.myweb.flowmat.domain.production.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A confirmed plan against what its work order's finished runs did (docs/domain/plan-vs-actual.md). The plan is the plan of
 * the confirmation's revision and target; {@code planned} is that plan for the output the runs made, so a part made is
 * compared with the plan for that part. A variance is {@code (actual - planned) / planned} in percent; null when there is
 * nothing to compare yet.
 */
public record PlanActualResponse(
    String workOrderId,
    String workOrderStatus,
    String workflowId,
    String revisionId,
    OffsetDateTime confirmedAt,
    BigDecimal target,
    String targetUnit,
    /** What the finished runs made. */
    BigDecimal output,
    int finishedRuns,
    /** Runs not finished yet; what they used so far is left out. */
    int openRuns,
    BigDecimal materialTolerancePercent,
    BigDecimal timeTolerancePercent,
    List<Material> materials,
    List<Step> steps,
    /** Every line could be compared: no unit that does not convert and the plan could be made again. */
    boolean complete,
    List<String> notes
) {

    /** {@code plannedForTarget} is the plan's need for the whole target; {@code used} counts inputs not cancelled. */
    public record Material(String itemId, String itemName, String unit, BigDecimal plannedForTarget, BigDecimal planned,
        BigDecimal used, BigDecimal variancePercent, Boolean within) {
    }

    /** Minutes; {@code actual} adds each finished run's step from its start to its end, {@code measured} counts them. */
    public record Step(String processId, String processName, BigDecimal plannedForTarget, BigDecimal planned,
        BigDecimal actual, int measured, BigDecimal variancePercent, Boolean within) {
    }
}
