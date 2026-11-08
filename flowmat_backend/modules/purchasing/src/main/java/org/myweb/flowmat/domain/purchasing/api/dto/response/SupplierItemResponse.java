package org.myweb.flowmat.domain.purchasing.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** An item's usual supplier (docs/domain/purchasing.md PO29-PO31). */
public record SupplierItemResponse(String supplierItemId, String projectId, String itemId, String itemCode, String supplierId,
                                   String supplierName, boolean supplierActive, String supplierItemCode, BigDecimal unitPrice, long version,
                                   String updatedBy,
                                   OffsetDateTime updatedAt) {
}
