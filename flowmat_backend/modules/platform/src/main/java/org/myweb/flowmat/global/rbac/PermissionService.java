package org.myweb.flowmat.global.rbac;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.user.domain.entity.User;
import org.myweb.flowmat.domain.user.domain.entity.UserRole;
import org.myweb.flowmat.domain.user.repository.RolePermissionRepository;
import org.myweb.flowmat.domain.user.repository.UserRepository;
import org.myweb.flowmat.domain.user.repository.UserRoleRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.security.AuthUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PermissionService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;

    public String requireCurrentUserId() {
        String userId = resolveCurrentUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    public void require(SystemPermission permission) {
        if (!hasPermission(permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                "Permission required: " + permission.getCode());
        }
    }

    public boolean hasPermission(SystemPermission permission) {
        String userId = resolveCurrentUserId();
        if (userId == null) {
            return false;
        }
        User user = userRepository.findByUserId(userId).orElse(null);
        if (user == null || user.getId() == null) {
            return false;
        }
        List<UserRole> userRoles = userRoleRepository.findByUserIdAndScopeType(user.getId(), "global");
        if (userRoles.isEmpty()) {
            return false;
        }
        Set<UUID> roleIds = userRoles.stream()
            .map(UserRole::getRoleId)
            .collect(Collectors.toSet());
        return rolePermissionRepository.existsByRoleIdInAndPermissionCode(roleIds, permission.getCode());
    }

    private static String resolveCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            return null;
        }
        if (auth.getPrincipal() instanceof AuthUser authUser
            && authUser.getUserId() != null
            && !authUser.getUserId().isBlank()) {
            return authUser.getUserId();
        }
        String name = auth.getName();
        return (name != null && !name.isBlank()) ? name : null;
    }
}
