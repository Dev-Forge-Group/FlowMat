package org.myweb.flowmat.domain.production.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record ProductionRunItemRecordRequest(
    String processId,
    String processIoId,
    String inventoryId,
    @NotBlank String itemId,
    @NotBlank String direction,
    @NotNull BigDecimal plannedQty,
    BigDecimal actualQty,
    @NotBlank(message = "unit is required.") String unit,
    /**
     * Optional key of this recording, at most 100 characters. Sent again with the same contents after a lost reply, it
     * returns what was recorded instead of recording twice (docs/domain/production-run-recording.md RR1-RR2).
     */
    String requestId
) {
    /** A recording without a request key, as before RR1. */
    public ProductionRunItemRecordRequest(String processId, String processIoId, String inventoryId, String itemId,
        String direction, BigDecimal plannedQty, BigDecimal actualQty, String unit) {
        this(processId, processIoId, inventoryId, itemId, direction, plannedQty, actualQty, unit, null);
    }
}
