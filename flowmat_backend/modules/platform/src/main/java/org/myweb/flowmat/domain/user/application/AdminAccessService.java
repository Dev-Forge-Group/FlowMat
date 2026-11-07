package org.myweb.flowmat.domain.user.application;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.global.rbac.PermissionService;
import org.myweb.flowmat.global.rbac.SystemPermission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminAccessService {

    private final PermissionService permissionService;

    public void requireAdmin() {
        permissionService.require(SystemPermission.USER_MANAGE);
    }
}
