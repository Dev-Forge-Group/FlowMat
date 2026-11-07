package org.myweb.flowmat.domain.notification.application.publicapi;

/** One person's choice of a topic in a project; {@code daysAhead} only for work_order_due (NS1). */
public record NotificationSubscriptionView(String projectId, String userId, String topic, Integer daysAhead) {
}
