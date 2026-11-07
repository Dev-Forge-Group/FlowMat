package org.myweb.flowmat.domain.production.api.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/** A correction of a finished run (docs/domain/production-run-correction.md). */
public record RunCorrectionCreateRequest(
    @NotBlank(message = "reason is required.") @Size(max = 500) String reason,
    @NotEmpty @Size(max = 50) List<@Valid RunCorrectionLineRequest> lines
) {
}
