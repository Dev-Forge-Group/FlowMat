package org.myweb.flowmat.domain.catalog.api.dto.request;

import java.math.BigDecimal;

/** Explicit null clears the rate. expectedVersion is the version loaded by the editor. */
public record EquipmentHourlyCostRequest(BigDecimal hourlyCost, long expectedVersion) {
}
