package org.myweb.flowmat.domain.inventory.api.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record LotResponse(
    String lotId,
    String projectId,
    String itemId,
    String lotNo,
    String serialNo,
    String lotStatus,
    OffsetDateTime receivedAt,
    OffsetDateTime producedAt,
    LocalDate expiryDate,
    String productionRunId,
    /** Summed over every active stock record of this LOT. */
    BigDecimal quantityOnHand,
    BigDecimal quantityReserved,
    /** Past its expiry date today; expired stock cannot go into production or be reserved. */
    boolean expired
) {
}
