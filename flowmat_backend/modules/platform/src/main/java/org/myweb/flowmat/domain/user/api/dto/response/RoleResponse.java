package org.myweb.flowmat.domain.user.api.dto.response;

import java.util.UUID;

public record RoleResponse(
    UUID roleId,
    String roleName,
    String roleDescription
) {
}
