package org.myweb.flowmat.domain.production.api.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * Whether a work order can run now (docs/domain/work-order-readiness.md): its status, workflow, BOM and whether the
 * materials for the quantity still to produce are in stock. Nothing is reserved; stock can change before the run starts.
 */
public record WorkOrderReadinessResponse(
    String workOrderId,
    /** No check failed. Warnings do not block. */
    boolean ready,
    /** Target quantity minus what finished runs produced; null without a target quantity. */
    BigDecimal remainingQuantity,
    List<Check> checks,
    List<Material> materials
) {

    /** {@code status}: ok, warn or fail. */
    public record Check(String code, String status, String message) {
    }

    public record Material(
        String itemId,
        String itemCode,
        String itemName,
        /** For the remaining quantity, in {@link #unit}. */
        BigDecimal requiredQuantity,
        String unit,
        /** On hand minus reserved, over stock records that are not quarantined and whose LOT is not closed. */
        BigDecimal availableQuantity,
        BigDecimal shortageQuantity,
        boolean lotTracked,
        /** LOTs with usable stock; 0 for items without LOT tracking. */
        int usableLots
    ) {
    }
}
