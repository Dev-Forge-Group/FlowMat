package org.myweb.flowmat.domain.production.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RunCorrectionRejectRequest(@NotBlank(message = "note is required.") @Size(max = 500) String note) {
}
