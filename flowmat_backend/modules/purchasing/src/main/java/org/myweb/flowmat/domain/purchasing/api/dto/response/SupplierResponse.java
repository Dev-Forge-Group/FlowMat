package org.myweb.flowmat.domain.purchasing.api.dto.response;

import java.time.OffsetDateTime;

/** A supplier of a project (docs/domain/purchasing.md PO18-PO21). */
public record SupplierResponse(String supplierId, String projectId, String supplierName, String contact, Integer leadTimeDays,
                               String note, boolean active, long version, String createdBy, OffsetDateTime createdAt,
                               String updatedBy, OffsetDateTime updatedAt) {
}
