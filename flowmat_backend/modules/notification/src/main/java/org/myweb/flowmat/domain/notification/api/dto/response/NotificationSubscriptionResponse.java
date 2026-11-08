package org.myweb.flowmat.domain.notification.api.dto.response;

/** Whether the current person hears about a topic in a project (NS2); {@code daysAhead} only for a chosen work_order_due. */
public record NotificationSubscriptionResponse(String projectId, String topic, boolean subscribed, Integer daysAhead) {
}
