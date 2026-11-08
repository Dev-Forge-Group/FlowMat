package org.myweb.flowmat.domain.user.api.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record UserResponse(
    UUID id,
    String userId,
    String userName,
    String userNickname,
    String userEmail,
    String userTel,
    LocalDate userBirth,
    String userRole,
    String userStatus,
    String emailVerifiedYn,
    String avatarUrl,
    OffsetDateTime lastLoginAt
) {
}
