package org.myweb.flowmat.domain.production.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A run's equipment running time and its cost at the rates recorded with it (docs/domain/equipment-setup-cost.md ER4).
 * Shown apart from material cost and never added to it.
 *
 * @param defaultEquipmentId the run's work order's equipment, which a new record uses unless another is named
 * @param suggestedMinutes   the run's time so far (to its finish once finished) less the setups and running time that
 *                           count, whole minutes; null when nothing is left to suggest
 * @param runningMinutes     minutes of the records that count (not cancelled)
 * @param runningCost        their known cost, 4 decimals
 * @param costComplete       false when a counted record's equipment had no rate, so {@link #runningCost} leaves it out
 * @param lines              every record, cancelled ones included, oldest first; each says how its rate was set
 *                           ({@code recorded}, or {@code historical}/{@code estimated} when a correction added it, ER11)
 */
public record RunEquipmentTimeResponse(
    String productionRunId,
    String defaultEquipmentId,
    Integer suggestedMinutes,
    int runningMinutes,
    BigDecimal runningCost,
    boolean costComplete,
    List<Line> lines
) {
    public record Line(
        String runEquipmentTimeId,
        String equipmentId,
        String equipmentLabel,
        int runningMinutes,
        BigDecimal hourlyCost,
        long hourlyCostVersion,
        BigDecimal runningCost,
        String note,
        String recordedBy,
        OffsetDateTime recordedAt,
        boolean cancelled,
        String cancelledBy,
        OffsetDateTime cancelledAt,
        String cancelReason,
        String rateBasis
    ) {
    }
}
