package org.myweb.flowmat.domain.notification.api.dto.response;

import java.time.OffsetDateTime;

/** One of the current person's notifications (docs/domain/notification.md NT3). */
public record NotificationResponse(
    String notificationId,
    String type,
    String title,
    String body,
    String projectId,
    String refType,
    String refId,
    boolean read,
    OffsetDateTime readAt,
    OffsetDateTime createdAt
) {
}
