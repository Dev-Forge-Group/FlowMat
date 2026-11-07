package org.myweb.flowmat.domain.inventory.api.dto.response;

import java.math.BigDecimal;
import java.util.List;

/** What a stock count changed; lines without a difference have no transaction. */
public record InventoryCountResponse(
    String countId,
    int adjusted,
    int unchanged,
    List<Line> lines
) {

    public record Line(
        String inventoryId,
        String itemId,
        String location,
        String lotId,
        BigDecimal quantityBefore,
        BigDecimal countedQuantity,
        BigDecimal difference,
        String inventoryTransactionId
    ) {
    }
}
