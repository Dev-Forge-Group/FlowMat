package org.myweb.flowmat.domain.catalog.application.publicapi;

import java.math.BigDecimal;

/**
 * What another bounded context may read about a piece of equipment. {@code capacityPerHour} is null when unset;
 * {@code efficiencyPercent} is null for 100 (docs/domain/equipment.md E8). {@code concurrentWorkOrders}: several work
 * orders may run on it at once, so plans do not keep them apart (E12); {@code concurrentLimit}: at most that many, null for
 * no limit (E16).
 */
public record CatalogEquipmentView(
    String equipmentId,
    String projectId,
    String equipmentCode,
    String equipmentName,
    String equipmentStatus,
    BigDecimal capacityPerHour,
    BigDecimal efficiencyPercent,
    boolean concurrentWorkOrders,
    Integer concurrentLimit
) {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * The output per hour it really reaches: capacity per hour times its efficiency (E9). Null without a capacity. Plans
     * reckon with this; changeover minutes are not scaled by it.
     */
    public BigDecimal effectiveCapacityPerHour() {
        if (capacityPerHour == null || efficiencyPercent == null || efficiencyPercent.compareTo(HUNDRED) == 0) {
            return capacityPerHour;
        }
        return capacityPerHour.multiply(efficiencyPercent).divide(HUNDRED);
    }

    /** How many work orders may run on it at once: 1, its limit, or null for no limit (E12, E16). */
    public Integer maxConcurrent() {
        return concurrentWorkOrders ? concurrentLimit : Integer.valueOf(1);
    }

    /** The code, or the name when there is no code. */
    public String label() {
        return equipmentCode != null ? equipmentCode : equipmentName;
    }
}
