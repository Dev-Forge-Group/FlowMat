package org.myweb.flowmat.domain.user.api.dto.request;

import jakarta.validation.constraints.Email;
import java.time.LocalDate;

public record UserUpdateRequest(
    String userName,
    String userNickname,
    @Email String userEmail,
    String userTel,
    LocalDate userBirth,
    String avatarUrl,
    String currentPassword,
    String newPassword
) {

    @Override
    public String toString() {
        return "UserUpdateRequest[credentials=REDACTED]";
    }
}
