package org.myweb.flowmat.domain.production.api.dto.response;

import java.math.BigDecimal;

/**
 * A work order's planned running time beside what its runs recorded (docs/domain/equipment-setup-cost.md ER8). Planned is
 * the target quantity at the equipment's capacity per hour, at today's rate; actual adds up the runs' running time at the
 * rates it was recorded at (ER2). Neither is material cost.
 *
 * @param capacityPerHour    the equipment's output per hour; null when unset
 * @param plannedMinutes     target quantity / capacity per hour, whole minutes rounded up; null without equipment, capacity or target
 * @param plannedCost        plannedMinutes at the equipment's current rate; null when either is unknown
 * @param runs               physical runs of the order
 * @param actualCostComplete every counted record has a known rate
 * @param efficiencyPercent  the equipment's efficiency, null for 100; plannedMinutes is at capacity times this (equipment.md E9)
 */
public record WorkOrderRunningComparisonResponse(String workOrderId, String equipmentId, BigDecimal capacityPerHour,
    Integer plannedMinutes, BigDecimal plannedCost, int runs, int actualMinutes, BigDecimal actualCost, boolean actualCostComplete,
    BigDecimal efficiencyPercent) {
}
