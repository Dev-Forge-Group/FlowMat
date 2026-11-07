package org.myweb.flowmat.domain.catalog.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One change of an equipment's hourly rate (docs/domain/equipment-setup-cost.md AS9); null is unknown, zero is known. */
public record EquipmentHourlyCostChangeResponse(BigDecimal previousHourlyCost, BigDecimal hourlyCost, long version,
    String changedBy, OffsetDateTime changedAt) {
}
