package org.myweb.flowmat.domain.catalog.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** An item's current disposal cost per item unit; version 0 and null when none was ever saved. */
public record ItemDisposalCostResponse(String itemId, BigDecimal disposalCost, long version, String updatedBy,
    OffsetDateTime updatedAt) {
}
