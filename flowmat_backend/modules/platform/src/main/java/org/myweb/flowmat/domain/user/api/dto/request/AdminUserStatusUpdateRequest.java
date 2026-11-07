package org.myweb.flowmat.domain.user.api.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.myweb.flowmat.domain.user.domain.enums.UserStatus;

public record AdminUserStatusUpdateRequest(
    @NotNull UserStatus userStatus,
    @Size(max = 500) String reason
) {
}
