package org.myweb.flowmat.domain.production.api.dto.response;

import java.math.BigDecimal;

/**
 * A work order's planned setup beside what its runs recorded (docs/domain/equipment-setup-cost.md AS11). Planned is the
 * load board's changeover estimate (SC6) at today's rate; actual adds up the runs' setups at the rates they were recorded
 * at (AS2). Neither is material cost.
 *
 * @param plannedMinutes changeover from the order before it on its equipment; null without equipment, item or such an order
 * @param plannedCost plannedMinutes at the equipment's current rate; null when either is unknown
 * @param runs physical runs of the order
 * @param actualCostComplete every counted setup has a known rate
 */
public record WorkOrderSetupComparisonResponse(String workOrderId, String equipmentId, Integer plannedMinutes,
    BigDecimal plannedCost, int runs, int actualMinutes, BigDecimal actualCost, boolean actualCostComplete) {
}
