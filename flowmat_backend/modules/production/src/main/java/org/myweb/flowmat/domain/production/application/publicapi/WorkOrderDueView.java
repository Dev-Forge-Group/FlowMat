package org.myweb.flowmat.domain.production.application.publicapi;

import java.time.OffsetDateTime;

/** An approved or running work order with its planned end, for reminders (docs/domain/notification.md NS6). */
public record WorkOrderDueView(
    String workOrderId,
    String projectId,
    String workOrderNumber,
    String workOrderTitle,
    OffsetDateTime plannedEndAt
) {
}
