package org.myweb.flowmat.domain.user.api.dto.request;

import jakarta.validation.constraints.NotBlank;

public record DormantReactivationRequest(
    @NotBlank String userIdOrEmail
) {
}
