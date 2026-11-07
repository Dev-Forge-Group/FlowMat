package org.myweb.flowmat.domain.user.api.admin;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.user.api.dto.request.AdminUserStatusUpdateRequest;
import org.myweb.flowmat.domain.user.api.dto.request.GrantRoleRequest;
import org.myweb.flowmat.domain.user.api.dto.response.AdminUserActionLogResponse;
import org.myweb.flowmat.domain.user.api.dto.response.RoleResponse;
import org.myweb.flowmat.domain.user.api.dto.response.UserResponse;
import org.myweb.flowmat.domain.user.api.dto.response.UserRoleResponse;
import org.myweb.flowmat.domain.user.application.UserRoleService;
import org.myweb.flowmat.domain.user.application.UserService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/users")
public class AdminUserController {

    private final UserRoleService userRoleService;
    private final UserService userService;

    @GetMapping
    public ApiResponse<List<UserResponse>> searchUsers(
        @RequestParam(value = "q", defaultValue = "") String query
    ) {
        return ApiResponse.ok(userService.searchUsers(query));
    }

    @PatchMapping("/{userId}/status")
    public ApiResponse<UserResponse> updateStatus(
        @PathVariable("userId") String userId,
        @Valid @RequestBody AdminUserStatusUpdateRequest request
    ) {
        return ApiResponse.ok(userService.updateStatus(userId, request));
    }

    @GetMapping("/{userId}/activity")
    public ApiResponse<List<AdminUserActionLogResponse>> listAdminActivity(
        @PathVariable("userId") String userId
    ) {
        return ApiResponse.ok(userService.listAdminActivity(userId));
    }

    @GetMapping("/roles")
    public ApiResponse<List<RoleResponse>> listRoles() {
        return ApiResponse.ok(userRoleService.listRoles());
    }

    @GetMapping("/{userId}/roles")
    public ApiResponse<List<UserRoleResponse>> listUserRoles(@PathVariable("userId") String userId) {
        return ApiResponse.ok(userRoleService.listUserRoles(userId));
    }

    @PostMapping("/{userId}/roles")
    public ApiResponse<UserRoleResponse> grantRole(
        @PathVariable("userId") String userId,
        @Valid @RequestBody GrantRoleRequest request
    ) {
        return ApiResponse.ok(userRoleService.grantRole(userId, request.roleName()));
    }

    @DeleteMapping("/{userId}/roles/{userRolesId}")
    public ApiResponse<Void> revokeRole(
        @PathVariable("userId") String userId,
        @PathVariable("userRolesId") UUID userRolesId
    ) {
        userRoleService.revokeRole(userRolesId);
        return ApiResponse.ok(null);
    }
}
