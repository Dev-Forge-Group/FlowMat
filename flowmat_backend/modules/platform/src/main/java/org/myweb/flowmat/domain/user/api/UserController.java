package org.myweb.flowmat.domain.user.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.user.api.dto.request.SocialLinkUnlinkRequest;
import org.myweb.flowmat.domain.user.api.dto.request.UserUpdateRequest;
import org.myweb.flowmat.domain.user.api.dto.response.UserPermissionResponse;
import org.myweb.flowmat.domain.user.api.dto.response.SocialAccountResponse;
import org.myweb.flowmat.domain.user.api.dto.response.UserResponse;
import org.myweb.flowmat.domain.user.application.UserService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.rbac.PermissionService;
import org.myweb.flowmat.global.rbac.SystemPermission;
import org.myweb.flowmat.global.response.ApiResponse;
import org.myweb.flowmat.global.security.AuthUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/users")
public class UserController {

    private final UserService userService;
    private final PermissionService permissionService;

    @GetMapping("/me")
    public ApiResponse<UserResponse> me(@AuthenticationPrincipal AuthUser authUser) {
        requireAuth(authUser);
        return ApiResponse.ok(userService.me(authUser.getUserId()));
    }

    @GetMapping("/me/permissions")
    public ApiResponse<UserPermissionResponse> myPermissions(@AuthenticationPrincipal AuthUser authUser) {
        requireAuth(authUser);
        return ApiResponse.ok(new UserPermissionResponse(
            permissionService.hasPermission(SystemPermission.USER_MANAGE),
            permissionService.hasPermission(SystemPermission.TEMPLATE_MANAGE),
            permissionService.hasPermission(SystemPermission.MASTER_DATA_MANAGE)
        ));
    }

    @GetMapping("/me/social-accounts")
    public ApiResponse<List<SocialAccountResponse>> mySocialAccounts(@AuthenticationPrincipal AuthUser authUser) {
        requireAuth(authUser);
        return ApiResponse.ok(userService.mySocialAccounts(authUser.getUserId()));
    }

    @PostMapping("/me/social-accounts/unlink")
    public ApiResponse<Void> unlinkSocialAccount(
        @AuthenticationPrincipal AuthUser authUser,
        @Valid @RequestBody SocialLinkUnlinkRequest request
    ) {
        requireAuth(authUser);
        userService.unlinkSocialAccount(authUser.getUserId(), request);
        return ApiResponse.ok(null);
    }

    @PatchMapping("/me")
    public ApiResponse<UserResponse> updateMe(
        @AuthenticationPrincipal AuthUser authUser,
        @Valid @RequestBody UserUpdateRequest request
    ) {
        requireAuth(authUser);
        return ApiResponse.ok(userService.updateMe(authUser.getUserId(), request));
    }

    @PostMapping("/me/dormant")
    public ApiResponse<Void> requestDormant(@AuthenticationPrincipal AuthUser authUser) {
        requireAuth(authUser);
        userService.requestDormant(authUser.getUserId());
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/me")
    public ApiResponse<Void> deleteMe(@AuthenticationPrincipal AuthUser authUser) {
        requireAuth(authUser);
        userService.deleteMe(authUser.getUserId());
        return ApiResponse.ok(null);
    }

    private void requireAuth(AuthUser authUser) {
        if (authUser == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}
