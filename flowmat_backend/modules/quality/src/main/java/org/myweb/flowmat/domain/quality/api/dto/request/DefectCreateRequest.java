package org.myweb.flowmat.domain.quality.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Logs one defect (docs/domain/quality-inspection.md). Run, LOT and item are taken from the inspection when one is given.
 *
 * @param quantity how much is defective, in the item's unit.
 * @param severity {@code minor} (default), {@code major} or {@code critical}.
 */
public record DefectCreateRequest(
    @NotBlank String projectId,
    String inspectionId,
    String productionRunId,
    String lotId,
    String itemId,
    @NotNull @Positive BigDecimal quantity,
    @NotBlank @Size(max = 50) String defectType,
    @Size(max = 20) String severity,
    @Size(max = 2000) String reason
) {
}
