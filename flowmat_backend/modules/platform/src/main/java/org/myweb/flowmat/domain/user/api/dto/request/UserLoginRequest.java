package org.myweb.flowmat.domain.user.api.dto.request;

import jakarta.validation.constraints.NotBlank;

public record UserLoginRequest(
    @NotBlank String userIdOrEmail,
    @NotBlank String password,
    String deviceId
) {

    @Override
    public String toString() {
        return "UserLoginRequest[credentials=REDACTED]";
    }
}
