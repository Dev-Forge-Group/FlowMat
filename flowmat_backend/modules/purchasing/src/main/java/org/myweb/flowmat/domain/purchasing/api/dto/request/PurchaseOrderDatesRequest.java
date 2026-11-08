package org.myweb.flowmat.domain.purchasing.api.dto.request;

import java.util.List;

/**
 * New expected dates of an order being received, with the reason (docs/domain/purchasing.md PO22).
 *
 * @param expectedDate the order's own, sent whole: a date like 2041-09-10, empty to clear it
 * @param lines        only the lines to move; the others keep theirs
 */
public record PurchaseOrderDatesRequest(String expectedDate, List<Line> lines, String reason) {

    /** @param expectedDate a date like 2041-09-10; empty clears it */
    public record Line(String lineId, String expectedDate) {
    }
}
