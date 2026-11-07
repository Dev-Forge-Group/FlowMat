package org.myweb.flowmat.domain.notification.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.notification.api.dto.request.NotificationSubscriptionRequest;
import org.myweb.flowmat.domain.notification.api.dto.response.NotificationSubscriptionResponse;
import org.myweb.flowmat.domain.notification.application.publicapi.NotificationSubscriptionQuery;
import org.myweb.flowmat.domain.notification.application.publicapi.NotificationSubscriptionView;
import org.myweb.flowmat.domain.notification.domain.entity.NotificationSubscription;
import org.myweb.flowmat.domain.notification.repository.NotificationSubscriptionRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The notifications a person chooses in a project (docs/domain/notification.md NS1-NS2; user decision 2026-10-13). Anyone
 * who can read the project chooses for themselves only; nobody chooses for someone else.
 */
@Service
@RequiredArgsConstructor
public class NotificationSubscriptionService implements NotificationSubscriptionQuery {

    private static final int DEFAULT_DAYS = 3;
    private static final int MAX_DAYS = 30;

    private final NotificationSubscriptionRepository repository;
    private final ProjectAccessService access;
    private final IdGenerator ids;

    @Transactional(readOnly = true)
    public List<NotificationSubscriptionResponse> mine(String projectId) {
        String project = requireProject(projectId);
        access.requireProjectReadAccess(project);
        return responses(project, access.requireCurrentUserId());
    }

    @Transactional
    public List<NotificationSubscriptionResponse> set(NotificationSubscriptionRequest request) {
        String project = requireProject(request == null ? null : request.projectId());
        access.requireProjectReadAccess(project);
        String user = access.requireCurrentUserId();
        String topic = request.topic() == null ? "" : request.topic().trim().toLowerCase(Locale.ROOT);
        if (!TOPICS.contains(topic)) {
            throw bad("Unknown topic '" + request.topic() + "'. Use stock_alert, ncr_check_overdue or work_order_due.");
        }
        if (request.subscribed() == null) {
            throw bad("subscribed is required.");
        }
        Integer days = request.daysAhead();
        if (!WORK_ORDER_DUE.equals(topic) && days != null) {
            throw bad("daysAhead is only for work_order_due.");
        }
        if (WORK_ORDER_DUE.equals(topic) && request.subscribed()) {
            days = days == null ? DEFAULT_DAYS : days;
            if (days < 0 || days > MAX_DAYS) {
                throw bad("daysAhead must be whole days from 0 to " + MAX_DAYS + ".");
            }
        }
        Optional<NotificationSubscription> current = repository.findByProjectIdAndUserIdAndTopic(project, user, topic);
        OffsetDateTime now = OffsetDateTime.now();
        if (request.subscribed()) {
            NotificationSubscription row = current.orElseGet(() -> {
                NotificationSubscription created = new NotificationSubscription();
                created.setSubscriptionId(ids.generate());
                created.setProjectId(project);
                created.setUserId(user);
                created.setTopic(topic);
                created.setCreatedAt(now);
                return created;
            });
            row.setDaysAhead(WORK_ORDER_DUE.equals(topic) ? days : null);
            row.setUpdatedAt(now);
            try {
                repository.saveAndFlush(row);
            } catch (DataIntegrityViolationException raced) {
                throw new BusinessException(ErrorCode.CONFLICT, "This choice was changed at the same time; reload and try again.");
            }
        } else {
            current.ifPresent(repository::delete);
            repository.flush();
        }
        return responses(project, user);
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationSubscriptionView> subscriptions(String topic) {
        return repository.findAllByTopic(topic).stream()
            .map(row -> new NotificationSubscriptionView(row.getProjectId(), row.getUserId(), row.getTopic(), row.getDaysAhead()))
            .toList();
    }

    private List<NotificationSubscriptionResponse> responses(String projectId, String userId) {
        List<NotificationSubscription> rows = repository.findAllByProjectIdAndUserId(projectId, userId);
        return TOPICS.stream()
            .map(topic -> rows.stream().filter(row -> topic.equals(row.getTopic())).findFirst()
                .map(row -> new NotificationSubscriptionResponse(projectId, topic, true, row.getDaysAhead()))
                .orElseGet(() -> new NotificationSubscriptionResponse(projectId, topic, false, null)))
            .toList();
    }

    private static String requireProject(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw bad("projectId is required.");
        }
        return projectId.trim();
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
