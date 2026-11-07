package org.myweb.flowmat.domain.purchasing.api.dto.request;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.math.BigDecimal;

/**
 * An item's usual supplier, set or cleared (docs/domain/purchasing.md PO29).
 *
 * @param supplierId      empty to clear it
 * @param expectedVersion the version the editor loaded; empty when the item had none
 * @param unitPrice       the supplier's price per stock unit, 0 or more; empty when not agreed (PO38)
 */
public record SupplierItemRequest(String projectId, String itemId, String supplierId, String supplierItemCode,
                                  @JsonDeserialize(using = VersionDeserializer.class) Long expectedVersion,
                                  BigDecimal unitPrice) {
}
