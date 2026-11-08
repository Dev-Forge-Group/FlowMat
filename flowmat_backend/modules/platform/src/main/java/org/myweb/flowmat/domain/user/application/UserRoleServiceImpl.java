package org.myweb.flowmat.domain.user.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.user.api.dto.response.RoleResponse;
import org.myweb.flowmat.domain.user.api.dto.response.UserRoleResponse;
import org.myweb.flowmat.domain.user.domain.entity.Role;
import org.myweb.flowmat.domain.user.domain.entity.User;
import org.myweb.flowmat.domain.user.domain.entity.UserRole;
import org.myweb.flowmat.domain.user.domain.enums.AdminUserActionType;
import org.myweb.flowmat.domain.user.repository.RoleRepository;
import org.myweb.flowmat.domain.user.repository.UserRepository;
import org.myweb.flowmat.domain.user.repository.UserRoleRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.rbac.PermissionService;
import org.myweb.flowmat.global.rbac.SystemPermission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserRoleServiceImpl implements UserRoleService {

    private final PermissionService permissionService;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final AdminUserActionLogService adminUserActionLogService;

    @Override
    public List<RoleResponse> listRoles() {
        permissionService.require(SystemPermission.USER_MANAGE);
        return roleRepository.findAll().stream()
            .sorted(java.util.Comparator.comparing(Role::getRoleName))
            .map(r -> new RoleResponse(r.getRoleId(), r.getRoleName(), r.getRoleDescription()))
            .toList();
    }

    @Override
    public List<UserRoleResponse> listUserRoles(String userId) {
        permissionService.require(SystemPermission.USER_MANAGE);
        User user = findUser(userId);
        List<UserRole> userRoles = userRoleRepository.findByUserIdAndScopeType(user.getId(), "global");
        Map<UUID, Role> roleMap = buildRoleMap(userRoles);
        return userRoles.stream()
            .map(ur -> toResponse(ur, userId, roleMap))
            .toList();
    }

    @Override
    @Transactional
    public UserRoleResponse grantRole(String userId, String roleName) {
        permissionService.require(SystemPermission.USER_MANAGE);
        User user = findUser(userId);
        Role role = roleRepository.findByRoleName(roleName.trim().toLowerCase())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Role not found: " + roleName));

        boolean alreadyGranted = userRoleRepository.findByUserIdAndScopeType(user.getId(), "global")
            .stream()
            .anyMatch(ur -> ur.getRoleId().equals(role.getRoleId()));
        if (alreadyGranted) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Role already granted.");
        }

        UserRole userRole = new UserRole();
        userRole.setUserRolesId(UUID.randomUUID());
        userRole.setUserId(user.getId());
        userRole.setRoleId(role.getRoleId());
        userRole.setScopeType("global");
        userRole.setGrantedAt(OffsetDateTime.now());
        UserRole saved = userRoleRepository.save(userRole);
        adminUserActionLogService.record(userId, AdminUserActionType.ROLE_GRANT, null, role.getRoleName(), null);
        return toResponse(saved, userId, Map.of(role.getRoleId(), role));
    }

    @Override
    @Transactional
    public void revokeRole(UUID userRolesId) {
        permissionService.require(SystemPermission.USER_MANAGE);
        UserRole userRole = userRoleRepository.findById(userRolesId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        User user = userRepository.findById(userRole.getUserId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found."));
        Role role = roleRepository.findById(userRole.getRoleId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Role not found."));
        userRoleRepository.delete(userRole);
        adminUserActionLogService.record(user.getUserId(), AdminUserActionType.ROLE_REVOKE, role.getRoleName(), null, null);
    }

    private User findUser(String userId) {
        return userRepository.findByUserId(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found: " + userId));
    }

    private Map<UUID, Role> buildRoleMap(List<UserRole> userRoles) {
        List<UUID> roleIds = userRoles.stream().map(UserRole::getRoleId).distinct().toList();
        return roleRepository.findAllById(roleIds).stream()
            .collect(Collectors.toMap(Role::getRoleId, r -> r));
    }

    private static UserRoleResponse toResponse(UserRole ur, String userId, Map<UUID, Role> roleMap) {
        Role role = roleMap.get(ur.getRoleId());
        return new UserRoleResponse(
            ur.getUserRolesId(),
            userId,
            ur.getRoleId(),
            role != null ? role.getRoleName() : null,
            ur.getScopeType(),
            ur.getGrantedAt()
        );
    }
}
