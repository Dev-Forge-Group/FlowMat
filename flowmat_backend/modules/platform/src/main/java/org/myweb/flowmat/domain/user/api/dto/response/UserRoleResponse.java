package org.myweb.flowmat.domain.user.api.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UserRoleResponse(
    UUID userRolesId,
    String userId,
    UUID roleId,
    String roleName,
    String scopeType,
    OffsetDateTime grantedAt
) {
}
