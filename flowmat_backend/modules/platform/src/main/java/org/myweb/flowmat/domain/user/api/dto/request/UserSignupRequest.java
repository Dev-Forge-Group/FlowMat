package org.myweb.flowmat.domain.user.api.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record UserSignupRequest(
    @NotBlank String userId,
    @NotBlank String userName,
    @NotBlank String userNickname,
    @NotBlank @Email String userEmail,
    @NotBlank String userTel,
    @NotNull LocalDate userBirth,
    @NotBlank String password
) {

    @Override
    public String toString() {
        return "UserSignupRequest[credentials=REDACTED]";
    }
}
