package org.myweb.flowmat.domain.quality.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Records one inspection (docs/domain/quality-inspection.md).
 *
 * @param result        {@code pass} or {@code fail}; worked out by the server when a measured value and a limit are
 *                      given, and then it must agree if sent.
 * @param quarantineLot quarantine the whole LOT in the same transaction; only for a failed inspection of a LOT.
 * @param standardId    the inspection standard followed (docs/domain/inspection-standard.md): it sets the check, limits
 *                      and unit, and the item when neither a LOT nor an item is given.
 */
public record QualityInspectionCreateRequest(
    @NotBlank String projectId,
    String productionRunId,
    String lotId,
    String itemId,
    @NotBlank @Size(max = 50) String inspectionType,
    String result,
    BigDecimal measuredValue,
    BigDecimal standardMin,
    BigDecimal standardMax,
    @Size(max = 20) String unit,
    @Size(max = 2000) String note,
    Boolean quarantineLot,
    String standardId
) {
}
