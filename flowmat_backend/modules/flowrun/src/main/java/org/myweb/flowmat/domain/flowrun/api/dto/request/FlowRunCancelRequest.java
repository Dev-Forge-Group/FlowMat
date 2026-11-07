package org.myweb.flowmat.domain.flowrun.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FlowRunCancelRequest(@NotBlank @Size(max = 4000) String reason) {
}
