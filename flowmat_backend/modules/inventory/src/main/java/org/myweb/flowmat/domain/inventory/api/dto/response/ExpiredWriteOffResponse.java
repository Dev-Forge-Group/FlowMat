package org.myweb.flowmat.domain.inventory.api.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a write-off of expired stock did, one line per LOT (docs/domain/lot-expiry.md "만료 재고 폐기").
 *
 * @param value the written-off quantities at today's unit costs
 * @param valueComplete false when a written-off item has no unit cost; the value leaves it out
 */
public record ExpiredWriteOffResponse(int lots, BigDecimal value, boolean valueComplete, List<Line> lines) {

    /**
     * @param writtenOff in the item's unit
     * @param note why some stock stayed, e.g. a quarantined record or a reservation; null when all of it went
     */
    public record Line(String lotId, String lotNo, String itemId, String itemCode, String unit, BigDecimal writtenOff,
                       BigDecimal value, boolean closed, String note) {
    }
}
