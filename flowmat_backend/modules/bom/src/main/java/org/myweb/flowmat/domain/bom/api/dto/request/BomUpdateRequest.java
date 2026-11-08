package org.myweb.flowmat.domain.bom.api.dto.request;

import java.math.BigDecimal;

/** Header changes; draft revisions only. Null fields are left unchanged. */
public record BomUpdateRequest(
    String bomName,
    BigDecimal baseQuantity,
    String baseUnit,
    String note
) {
}
