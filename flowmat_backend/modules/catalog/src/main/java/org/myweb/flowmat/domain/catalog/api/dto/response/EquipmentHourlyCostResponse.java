package org.myweb.flowmat.domain.catalog.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record EquipmentHourlyCostResponse(String equipmentId, BigDecimal hourlyCost, long version,
    String updatedBy, OffsetDateTime updatedAt) {
}
