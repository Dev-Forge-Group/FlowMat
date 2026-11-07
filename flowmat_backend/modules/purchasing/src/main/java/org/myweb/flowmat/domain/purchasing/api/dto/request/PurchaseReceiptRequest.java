package org.myweb.flowmat.domain.purchasing.api.dto.request;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** What arrived, line by line, all or nothing (docs/domain/purchasing.md PO5). */
public record PurchaseReceiptRequest(UUID requestId, List<Line> lines) {

    /**
     * @param lotNo      required for a LOT-tracked item
     * @param expiryDate a date like 2041-12-31, only with a LOT
     */
    public record Line(String lineId, BigDecimal quantity, String location, String lotNo, String expiryDate) {
    }
}
