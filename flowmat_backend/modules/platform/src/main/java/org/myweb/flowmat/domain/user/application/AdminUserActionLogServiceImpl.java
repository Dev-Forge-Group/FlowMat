package org.myweb.flowmat.domain.user.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.user.api.dto.response.AdminUserActionLogResponse;
import org.myweb.flowmat.domain.user.domain.entity.AdminUserActionLog;
import org.myweb.flowmat.domain.user.domain.enums.AdminUserActionType;
import org.myweb.flowmat.domain.user.repository.AdminUserActionLogRepository;
import org.myweb.flowmat.global.security.AuthUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminUserActionLogServiceImpl implements AdminUserActionLogService {

    private final AdminUserActionLogRepository adminUserActionLogRepository;

    @Override
    @Transactional
    public void record(String targetUserId, AdminUserActionType actionType, String previousValue, String newValue, String reason) {
        AdminUserActionLog actionLog = new AdminUserActionLog();
        actionLog.setActionLogId(UUID.randomUUID());
        actionLog.setActorUserId(resolveCurrentUserId());
        actionLog.setTargetUserId(targetUserId);
        actionLog.setActionType(actionType.name());
        actionLog.setPreviousValue(previousValue);
        actionLog.setNewValue(newValue);
        actionLog.setReason(trimToNull(reason));
        actionLog.setCreatedAt(OffsetDateTime.now());
        adminUserActionLogRepository.save(actionLog);
    }

    @Override
    public List<AdminUserActionLogResponse> listByTargetUserId(String targetUserId) {
        return adminUserActionLogRepository.findByTargetUserIdOrderByCreatedAtDesc(targetUserId).stream()
            .map(log -> new AdminUserActionLogResponse(
                log.getActionLogId(),
                log.getActorUserId(),
                log.getTargetUserId(),
                log.getActionType(),
                log.getPreviousValue(),
                log.getNewValue(),
                log.getReason(),
                log.getCreatedAt()
            ))
            .toList();
    }

    private String resolveCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return "system";
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof AuthUser authUser && hasText(authUser.getUserId())) {
            return authUser.getUserId();
        }
        if (hasText(authentication.getName())) {
            return authentication.getName().trim();
        }
        return "system";
    }

    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
