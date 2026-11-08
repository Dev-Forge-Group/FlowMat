package org.myweb.flowmat.domain.inventory.api.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a first-expiring-first issue took or a reservation held, one line per stock record (docs/domain/lot-expiry.md
 * "재고 출고 나눠 하기").
 *
 * @param action {@code issue} or {@code reserve}
 * @param unit the item's unit, which every quantity is in
 */
public record FefoIssueResponse(String itemId, String action, BigDecimal quantity, String unit, List<Line> lines) {

    /**
     * @param quantity issued or reserved from the record
     * @param quantityAfter what the record holds after the movement
     * @param reservedAfter what is reserved on the record after the movement
     */
    public record Line(String inventoryTransactionId, String inventoryId, String lotId, String lotNo, String location,
                       BigDecimal quantity, BigDecimal quantityAfter, BigDecimal reservedAfter) {
    }
}
