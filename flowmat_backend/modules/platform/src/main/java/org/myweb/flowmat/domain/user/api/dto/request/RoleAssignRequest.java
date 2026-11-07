package org.myweb.flowmat.domain.user.api.dto.request;

public record RoleAssignRequest(String roleId, String scopeType, String scopeId) {
}
