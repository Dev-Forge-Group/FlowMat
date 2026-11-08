package org.myweb.flowmat.domain.bom.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** First revision of a BOM for a target item. Later revisions come from POST /boms/{id}/revisions. */
public record BomCreateRequest(
    @NotBlank String projectId,
    @NotBlank String targetItemId,
    @NotBlank String bomName,
    @NotNull BigDecimal baseQuantity,
    @NotBlank String baseUnit,
    String note
) {
}
