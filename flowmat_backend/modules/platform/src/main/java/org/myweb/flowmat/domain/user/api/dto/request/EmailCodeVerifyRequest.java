package org.myweb.flowmat.domain.user.api.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record EmailCodeVerifyRequest(
    @NotBlank @Email String userEmail,
    @NotBlank String code
) {

    @Override
    public String toString() {
        return "EmailCodeVerifyRequest[credentials=REDACTED]";
    }
}
