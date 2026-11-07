package org.myweb.flowmat.domain.production.application;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrder;
import org.myweb.flowmat.domain.production.domain.enums.WorkOrderStatus;

/**
 * Which order on the same equipment comes just before another (docs/domain/equipment-changeover.md). Readiness and the
 * equipment load board both take the changeover from it.
 */
final class EquipmentSequence {

    private EquipmentSequence() {
    }

    /**
     * Of {@code others} (orders on the same equipment, not the one asked about), the approved, running or completed one
     * with a target item that starts last before {@code start}; null when there is none. Drafts and cancelled orders are
     * not planned work, so they never count.
     */
    static WorkOrder previous(OffsetDateTime start, List<WorkOrder> others) {
        return others.stream()
            .filter(other -> other.getTargetItemId() != null && other.getPlannedStartAt() != null
                && other.getPlannedStartAt().isBefore(start))
            .filter(other -> {
                WorkOrderStatus status = WorkOrderServiceImpl.status(other);
                boolean planned = status.acceptsRuns() || status == WorkOrderStatus.COMPLETED;
                boolean validWindow = other.getPlannedEndAt() == null
                    ? status == WorkOrderStatus.IN_PROGRESS
                    : other.getPlannedEndAt().isAfter(other.getPlannedStartAt());
                return planned && validWindow;
            })
            .max(Comparator.comparing(WorkOrder::getPlannedStartAt).thenComparing(WorkOrder::getWorkOrderId))
            .orElse(null);
    }
}
