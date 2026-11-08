package org.myweb.flowmat.domain.production.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A run's actual setups and their cost at the rates recorded with them (docs/domain/equipment-setup-cost.md AS5). Shown
 * apart from material cost and never added to it.
 *
 * @param defaultEquipmentId the run's work order's equipment, which a new setup uses unless another is named
 * @param setupMinutes       minutes of the setups that count (not cancelled)
 * @param setupCost          their known cost, 4 decimals
 * @param costComplete       false when a counted setup's equipment had no rate, so {@link #setupCost} leaves it out
 * @param lines              every setup, cancelled ones included, oldest first
 */
public record RunSetupCostResponse(
    String productionRunId,
    String defaultEquipmentId,
    int setupMinutes,
    BigDecimal setupCost,
    boolean costComplete,
    List<Line> lines
) {
    public record Line(
        String runSetupId,
        String equipmentId,
        String equipmentLabel,
        int setupMinutes,
        BigDecimal hourlyCost,
        long hourlyCostVersion,
        BigDecimal setupCost,
        String note,
        String recordedBy,
        OffsetDateTime recordedAt,
        boolean cancelled,
        String cancelledBy,
        OffsetDateTime cancelledAt,
        String cancelReason,
        /** recorded, historical or estimated (docs/domain/equipment-setup-cost.md AS10). */
        String rateBasis
    ) {
    }
}
