package org.myweb.flowmat.domain.quality.api.dto.response;

import java.math.BigDecimal;

public record InspectionStandardResponse(
    String standardId,
    String projectId,
    String itemId,
    String itemCode,
    String itemName,
    String inspectionType,
    String stage,
    BigDecimal standardMin,
    BigDecimal standardMax,
    String unit,
    boolean required,
    boolean active,
    String note
) {
}
