package org.myweb.flowmat.domain.user.api.dto.request;

import jakarta.validation.constraints.NotBlank;

public record PasswordResetConfirmRequest(
    @NotBlank String token,
    @NotBlank String newPassword
) {

    @Override
    public String toString() {
        return "PasswordResetConfirmRequest[credentials=REDACTED]";
    }
}
