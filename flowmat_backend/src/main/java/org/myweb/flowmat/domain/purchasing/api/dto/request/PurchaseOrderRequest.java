package org.myweb.flowmat.domain.purchasing.api.dto.request;

import java.math.BigDecimal;
import java.util.List;

/**
 * A draft purchase order, sent whole: every field and line replaces the stored one (docs/domain/purchasing.md PO2).
 *
 * @param projectId    only when creating
 * @param expectedDate a date like 2041-09-10; empty for none
 * @param deliverTo    where it is to arrive: a listed place when the project lists any; empty for none (PO39)
 */
public record PurchaseOrderRequest(String projectId, String supplierName, String expectedDate, String note, List<Line> lines, String deliverTo) {

    /**
     * @param quantity  in the item's stock unit
     * @param unitPrice per stock unit, 0 or more; empty while not agreed (PO14)
     */
    public record Line(String itemId, BigDecimal quantity, String expectedDate, String note, BigDecimal unitPrice) {
    }
}
