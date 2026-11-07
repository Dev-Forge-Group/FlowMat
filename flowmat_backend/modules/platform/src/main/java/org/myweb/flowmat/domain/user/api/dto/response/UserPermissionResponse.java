package org.myweb.flowmat.domain.user.api.dto.response;

public record UserPermissionResponse(boolean canManageUsers, boolean canManageTemplates, boolean canManageMasterData) {
}
