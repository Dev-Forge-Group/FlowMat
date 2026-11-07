package org.myweb.flowmat.domain.inventory.application.publicapi;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.myweb.flowmat.domain.inventory.domain.enums.LotStatus;

/**
 * What another bounded context may read about a LOT. {@code productionRunId} is the run that made it, null for a bought LOT;
 * {@code expiryDate} is null when it does not expire and {@code receivedAt} null when it was not received.
 */
public record LotView(
    String lotId,
    String projectId,
    String itemId,
    String lotNo,
    String lotStatus,
    String productionRunId,
    LocalDate expiryDate,
    OffsetDateTime receivedAt
) {

    /** Whether the LOT is past its expiry date on {@code today}, a project calendar day, as LotMaster counts it. */
    public boolean isExpiredOn(LocalDate today) {
        return expiryDate != null && expiryDate.isBefore(today);
    }

    /** Whether stock of the LOT can be issued, consumed or reserved; a LOT without a known status counts as available. */
    public boolean usable() {
        return LotStatus.fromCode(lotStatus).usable();
    }

    public boolean quarantined() {
        return LotStatus.QUARANTINED.code().equals(lotStatus);
    }

    public boolean closed() {
        return LotStatus.CLOSED.code().equals(lotStatus);
    }
}
