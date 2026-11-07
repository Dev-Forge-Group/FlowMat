package org.myweb.flowmat.domain.user.application;

import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.user.api.dto.request.AdminUserStatusUpdateRequest;
import org.myweb.flowmat.domain.user.api.dto.request.SocialLinkUnlinkRequest;
import org.myweb.flowmat.domain.user.api.dto.request.UserUpdateRequest;
import org.myweb.flowmat.domain.user.api.dto.response.AdminUserActionLogResponse;
import org.myweb.flowmat.domain.user.api.dto.response.SocialAccountResponse;
import org.myweb.flowmat.domain.user.api.dto.response.UserResponse;
import org.myweb.flowmat.domain.user.domain.entity.SocialAccount;
import org.myweb.flowmat.domain.user.domain.entity.User;
import org.myweb.flowmat.domain.user.domain.enums.AdminUserActionType;
import org.myweb.flowmat.domain.user.domain.enums.UserStatus;
import org.myweb.flowmat.domain.user.repository.SocialAccountRepository;
import org.myweb.flowmat.domain.user.repository.UserRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.rbac.PermissionService;
import org.myweb.flowmat.global.rbac.SystemPermission;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final SocialAccountRepository socialAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final PermissionService permissionService;
    private final AuthRedisStore authRedisStore;
    private final AdminUserActionLogService adminUserActionLogService;

    @Override
    @Transactional(readOnly = true)
    public UserResponse me(String userId) {
        return toResponse(userRepository.findByUserId(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found.")));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserResponse> searchUsers(String query) {
        permissionService.require(SystemPermission.USER_MANAGE);
        String q = query == null ? "" : query.trim();
        return userRepository
            .findByUserIdContainingIgnoreCaseOrUserNameContainingIgnoreCaseOrUserEmailContainingIgnoreCase(q, q, q)
            .stream()
            .map(this::toResponse)
            .toList();
    }

    @Override
    public UserResponse updateStatus(String userId, AdminUserStatusUpdateRequest request) {
        permissionService.require(SystemPermission.USER_MANAGE);
        if (request == null || request.userStatus() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "User status is required.");
        }

        User user = userRepository.findByUserId(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found."));
        UserStatus currentStatus = parseStatus(user.getUserStatus());
        UserStatus targetStatus = request.userStatus();

        if (currentStatus == UserStatus.WITHDRAWN && targetStatus != UserStatus.WITHDRAWN) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Withdrawn users cannot be restored.");
        }
        if (currentStatus == targetStatus) {
            return toResponse(user);
        }

        applyStatusTransition(user, targetStatus);
        adminUserActionLogService.record(
            userId,
            AdminUserActionType.STATUS_CHANGE,
            currentStatus == null ? null : currentStatus.name(),
            targetStatus.name(),
            request.reason()
        );
        return toResponse(user);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminUserActionLogResponse> listAdminActivity(String userId) {
        permissionService.require(SystemPermission.USER_MANAGE);
        userRepository.findByUserId(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found."));
        return adminUserActionLogService.listByTargetUserId(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SocialAccountResponse> mySocialAccounts(String userId) {
        userRepository.findByUserId(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found."));
        return socialAccountRepository.findAllByUser_UserId(userId).stream()
            .map(this::toSocialResponse)
            .toList();
    }

    @Override
    public void unlinkSocialAccount(String userId, SocialLinkUnlinkRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
        User user = userRepository.findByUserId(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found."));
        if (!passwordEncoder.matches(normalizeRequired(request.currentPassword(), "Current password is required."), user.getUserPwd())) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }
        String provider = normalizeProvider(request.provider());
        SocialAccount account = socialAccountRepository.findByUser_UserIdAndProvider(userId, provider)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Social account not found."));
        socialAccountRepository.delete(account);
    }

    @Override
    public UserResponse updateMe(String userId, UserUpdateRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
        User user = userRepository.findByUserId(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found."));

        if (hasText(request.userName())) {
            user.setUserName(request.userName().trim());
        }
        if (hasText(request.userNickname())) {
            String nickname = request.userNickname().trim();
            if (!nickname.equals(user.getUserNickname()) && userRepository.existsByUserNickname(nickname)) {
                throw new BusinessException(ErrorCode.DUPLICATE_NICKNAME);
            }
            user.setUserNickname(nickname);
        }
        if (hasText(request.userEmail())) {
            String email = request.userEmail().trim().toLowerCase();
            if (!email.equalsIgnoreCase(user.getUserEmail()) && userRepository.existsByUserEmail(email)) {
                throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
            }
            user.setUserEmail(email);
            user.setEmailVerifiedYn("N");
            user.setEmailVerifiedAt(null);
        }
        if (hasText(request.userTel())) {
            String tel = request.userTel().trim();
            if (!tel.equals(user.getUserTel()) && userRepository.existsByUserTel(tel)) {
                throw new BusinessException(ErrorCode.DUPLICATE_TEL);
            }
            user.setUserTel(tel);
        }
        if (request.userBirth() != null) {
            user.setUserBirth(request.userBirth());
        }
        if (request.avatarUrl() != null) {
            user.setAvatarUrl(request.avatarUrl().trim());
        }
        if (hasText(request.newPassword())) {
            if (!hasText(request.currentPassword())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Current password is required.");
            }
            if (!passwordEncoder.matches(request.currentPassword().trim(), user.getUserPwd())) {
                throw new BusinessException(ErrorCode.INVALID_PASSWORD);
            }
            user.setUserPwd(passwordEncoder.encode(request.newPassword().trim()));
            user.setPwdUpdatedAt(OffsetDateTime.now());
        }

        return toResponse(user);
    }

    @Override
    public void requestDormant(String userId) {
        User user = userRepository.findByUserId(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found."));
        UserStatus currentStatus = parseStatus(user.getUserStatus());
        if (currentStatus == UserStatus.WITHDRAWN) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Withdrawn users cannot become dormant.");
        }
        if (currentStatus == UserStatus.DORMANT) {
            return;
        }
        applyStatusTransition(user, UserStatus.DORMANT);
        user.setDormantToken(null);
    }

    @Override
    public void deleteMe(String userId) {
        User user = userRepository.findByUserId(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found."));
        user.setDeleteYn("Y");
        user.setUserStatus("withdrawn");
        user.setWithdrawnAt(OffsetDateTime.now());
        authRedisStore.revokeAllRefreshTokens(userId);
    }

    private void applyStatusTransition(User user, UserStatus targetStatus) {
        OffsetDateTime now = OffsetDateTime.now();
        switch (targetStatus) {
            case ACTIVE -> {
                user.setUserStatus("active");
                user.setLockedAt(null);
                user.setDormantAt(null);
                user.setDormantToken(null);
                authRedisStore.clearLoginFailures(user.getUserId());
            }
            case LOCKED -> {
                user.setUserStatus("locked");
                user.setLockedAt(now);
                user.setDormantAt(null);
                user.setDormantToken(null);
                authRedisStore.revokeAllRefreshTokens(user.getUserId());
                authRedisStore.clearLoginFailures(user.getUserId());
            }
            case DORMANT -> {
                user.setUserStatus("dormant");
                user.setDormantAt(now);
                user.setLockedAt(null);
                authRedisStore.revokeAllRefreshTokens(user.getUserId());
                authRedisStore.clearLoginFailures(user.getUserId());
            }
            case WITHDRAWN -> {
                user.setUserStatus("withdrawn");
                user.setDeleteYn("Y");
                user.setWithdrawnAt(now);
                user.setDormantAt(null);
                user.setDormantToken(null);
                user.setLockedAt(null);
                authRedisStore.revokeAllRefreshTokens(user.getUserId());
                authRedisStore.clearLoginFailures(user.getUserId());
            }
        }
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(
            user.getId(),
            user.getUserId(),
            user.getUserName(),
            user.getUserNickname(),
            user.getUserEmail(),
            user.getUserTel(),
            user.getUserBirth(),
            user.getUserRole(),
            user.getUserStatus(),
            user.getEmailVerifiedYn(),
            user.getAvatarUrl(),
            user.getLastLoginAt()
        );
    }

    private SocialAccountResponse toSocialResponse(SocialAccount account) {
        return new SocialAccountResponse(
            account.getSocialAccountId(),
            account.getProvider(),
            account.getProviderEmail(),
            account.getCreatedAt()
        );
    }

    private String normalizeProvider(String provider) {
        String normalized = normalizeRequired(provider, "Provider is required.").toUpperCase();
        if (!"KAKAO".equals(normalized) && !"GOOGLE".equals(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Unsupported social provider.");
        }
        return normalized;
    }

    private String normalizeRequired(String value, String message) {
        if (!hasText(value)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        }
        return value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private UserStatus parseStatus(String status) {
        if (!hasText(status)) {
            return null;
        }
        return UserStatus.valueOf(status.trim().toUpperCase());
    }
}
