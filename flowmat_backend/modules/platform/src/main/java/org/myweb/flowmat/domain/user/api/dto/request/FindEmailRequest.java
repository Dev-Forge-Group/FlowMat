package org.myweb.flowmat.domain.user.api.dto.request;

import jakarta.validation.constraints.NotBlank;

public record FindEmailRequest(
    @NotBlank String userName,
    @NotBlank String userTel
) {
}
