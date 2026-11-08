package org.myweb.flowmat.domain.user.application;

import java.util.List;
import java.util.UUID;
import org.myweb.flowmat.domain.user.api.dto.response.RoleResponse;
import org.myweb.flowmat.domain.user.api.dto.response.UserRoleResponse;

public interface UserRoleService {

    List<RoleResponse> listRoles();

    List<UserRoleResponse> listUserRoles(String userId);

    UserRoleResponse grantRole(String userId, String roleName);

    void revokeRole(UUID userRolesId);
}
