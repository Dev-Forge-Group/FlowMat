package org.myweb.flowmat.domain.inventory.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Stock as it stood at a moment (docs/domain/stock-ledger.md "과거 시점 재고"), rebuilt from the ledger. Values use
 * today's unit costs.
 *
 * @param valueComplete false when some record's item has no unit cost; the total leaves it out
 */
public record StockSnapshotResponse(OffsetDateTime at, BigDecimal totalValue, boolean valueComplete, List<Row> rows) {

    /**
     * One stock record that held or reserved something at that moment.
     *
     * @param fromLedger false for a record that has never moved, whose quantity is simply what it was created with
     */
    public record Row(
        String inventoryId,
        String itemId,
        String itemCode,
        String itemName,
        String unit,
        String location,
        String lotId,
        String lotNo,
        BigDecimal quantity,
        BigDecimal reservedQuantity,
        BigDecimal value,
        boolean fromLedger
    ) {
    }
}
