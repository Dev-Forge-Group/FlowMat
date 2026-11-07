package org.myweb.flowmat.domain.quality.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Raises a nonconformity (docs/domain/nonconformity.md).
 *
 * @param severity minor, major or critical; the most severe of the defects (minor without defects) when empty
 * @param itemId item, LOT and run are taken from the first defect when not given
 * @param defectLogIds defects to gather on it; each may be on only one nonconformity
 */
public record NonconformityCreateRequest(
    @NotBlank String projectId,
    @NotBlank @Size(max = 200) String title,
    @Size(max = 2000) String description,
    String severity,
    String itemId,
    String lotId,
    String productionRunId,
    List<String> defectLogIds
) {
}
