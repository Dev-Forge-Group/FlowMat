package org.myweb.flowmat.domain.notification.application;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.notification.api.dto.response.NotificationResponse;
import org.myweb.flowmat.domain.notification.domain.entity.Notification;
import org.myweb.flowmat.domain.notification.repository.NotificationRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectMemberQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The current person's notifications (docs/domain/notification.md NT3-NT4): only their own, and only those about projects
 * they can still read, so someone who left a project is no longer told about its work.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final ProjectMemberQuery projectMemberQuery;
    private final ProjectAccessService projectAccessService;

    /** The newest hundred, newest first; only the unread ones when asked. */
    public List<NotificationResponse> mine(boolean unreadOnly) {
        String userId = projectAccessService.requireCurrentUserId();
        Predicate<Notification> readable = readable(userId);
        return notificationRepository.findTop100ByUserIdOrderByCreatedAtDescNfcIdDesc(userId).stream()
            .filter(notification -> !unreadOnly || !notification.isRead())
            .filter(readable)
            .map(NotificationService::response)
            .toList();
    }

    public int unreadCount() {
        String userId = projectAccessService.requireCurrentUserId();
        return (int) notificationRepository.findAllByUserIdAndRead(userId, false).stream().filter(readable(userId)).count();
    }

    /** Marks one of the person's own notifications read; reading it again changes nothing. */
    @Transactional
    public NotificationResponse markRead(String notificationId) {
        String userId = projectAccessService.requireCurrentUserId();
        Notification notification = notificationRepository.findByNfcIdAndUserId(notificationId, userId)
            .filter(readable(userId))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!notification.isRead()) {
            notification.setRead(true);
            notification.setReadAt(OffsetDateTime.now());
            notificationRepository.save(notification);
        }
        return response(notification);
    }

    /** Marks all of the person's unread notifications read; answers how many were. */
    @Transactional
    public int markAllRead() {
        String userId = projectAccessService.requireCurrentUserId();
        List<Notification> unread = notificationRepository.findAllByUserIdAndRead(userId, false).stream()
            .filter(readable(userId)).toList();
        OffsetDateTime now = OffsetDateTime.now();
        for (Notification notification : unread) {
            notification.setRead(true);
            notification.setReadAt(now);
        }
        notificationRepository.saveAll(unread);
        return unread.size();
    }

    /** One without a project always; one about a project while the person can read it, asked once per project. */
    private Predicate<Notification> readable(String userId) {
        Map<String, Boolean> projects = new HashMap<>();
        return notification -> notification.getProjectId() == null
            || projects.computeIfAbsent(notification.getProjectId(), projectId -> projectMemberQuery.canRead(projectId, userId));
    }

    private static NotificationResponse response(Notification notification) {
        return new NotificationResponse(notification.getNfcId(), notification.getNfcType(), notification.getNfcTitle(),
            notification.getNfcBody(), notification.getProjectId(), notification.getRefType(), notification.getRefId(),
            notification.isRead(), notification.getReadAt(), notification.getCreatedAt());
    }
}
