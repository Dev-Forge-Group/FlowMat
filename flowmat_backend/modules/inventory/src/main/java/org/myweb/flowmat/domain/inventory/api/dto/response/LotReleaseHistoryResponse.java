package org.myweb.flowmat.domain.inventory.api.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * When a LOT was released after its receipt checks and held for them again on reopening (docs/domain/lot-release.md R8),
 * from the stock ledger, and when it was closed and reopened (R9), newest first.
 */
public record LotReleaseHistoryResponse(String lotId, List<Event> events) {
    /**
     * @param action {@code released} (lot_inspection_release), {@code held} (lot_reopen), {@code closed} or {@code reopened}
     * @param stockRows how many stock records the event moved; 0 for closing and reopening
     * @param status the LOT's status after closing or reopening; null for the ledger's events
     */
    public record Event(String action, OffsetDateTime at, String by, int stockRows, String status) {}
}
