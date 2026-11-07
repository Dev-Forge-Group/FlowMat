package org.myweb.flowmat.domain.purchasing.api.dto.request;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.math.BigDecimal;

/**
 * A supplier, sent whole (docs/domain/purchasing.md PO18-PO19).
 *
 * @param projectId       only when adding
 * @param leadTimeDays    whole days from ordering to arrival, 0-365; empty when not known
 * @param active          only when changing; a new supplier is active
 * @param expectedVersion only when changing: the version the editor loaded
 */
public record SupplierRequest(String projectId, String supplierName, String contact, BigDecimal leadTimeDays, String note,
                              Boolean active, @JsonDeserialize(using = VersionDeserializer.class) Long expectedVersion) {
}
