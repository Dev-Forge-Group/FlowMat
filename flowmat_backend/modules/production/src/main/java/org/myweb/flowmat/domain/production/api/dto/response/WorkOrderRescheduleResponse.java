package org.myweb.flowmat.domain.production.api.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrderReschedule;

public record WorkOrderRescheduleResponse(String changeId, String workOrderId, UUID requestId,
    OffsetDateTime previousPlannedStartAt, OffsetDateTime previousPlannedEndAt,
    OffsetDateTime plannedStartAt, OffsetDateTime plannedEndAt, String reason, String changedBy, OffsetDateTime changedAt) {
    public static WorkOrderRescheduleResponse from(WorkOrderReschedule change) {
        return new WorkOrderRescheduleResponse(change.getChangeId(), change.getWorkOrderId(), change.getRequestId(),
            change.getPreviousPlannedStartAt(), change.getPreviousPlannedEndAt(), change.getPlannedStartAt(),
            change.getPlannedEndAt(), change.getReason(), change.getChangedBy(), change.getChangedAt());
    }
}
