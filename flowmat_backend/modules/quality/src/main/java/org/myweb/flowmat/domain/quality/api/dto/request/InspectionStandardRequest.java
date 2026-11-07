package org.myweb.flowmat.domain.quality.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Adds a standard, or replaces an existing one's check, stage, limits, unit, required flag and note (docs/domain/
 * inspection-standard.md). Empty limits mean no limit: the inspector then says pass or fail.
 *
 * @param projectId only when adding
 * @param itemId only when adding; a standard keeps its item
 * @param stage receipt, production or any; production when empty
 * @param active only when changing; empty keeps it
 */
public record InspectionStandardRequest(
    String projectId,
    String itemId,
    @NotBlank @Size(max = 50) String inspectionType,
    String stage,
    BigDecimal standardMin,
    BigDecimal standardMax,
    @Size(max = 20) String unit,
    Boolean required,
    Boolean active,
    @Size(max = 500) String note
) {
}
