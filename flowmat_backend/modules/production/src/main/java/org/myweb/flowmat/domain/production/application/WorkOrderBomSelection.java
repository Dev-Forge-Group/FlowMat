package org.myweb.flowmat.domain.production.application;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.bom.application.publicapi.BomRevisionQuery;
import org.myweb.flowmat.domain.bom.application.publicapi.EffectiveBomView;
import org.myweb.flowmat.domain.bom.domain.enums.BomStatus;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrder;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * Picks and checks a work order's BOM revision (DECISIONS-2026-10-05 section 5, docs/domain/multi-level-bom.md). The
 * revision is stored once; approval and rescheduling check it instead of silently replacing it.
 */
@Component
@RequiredArgsConstructor
class WorkOrderBomSelection {

    private static final String NOT_COVERED = "bomId does not cover plannedStartAt in the project time zone.";

    private final BomRevisionQuery revisions;
    private final ProjectCalendarQuery calendar;
    private final ProductionPlanningReferences references;

    /**
     * The revision to store. A requested one must make the target in this project; an approved one must also be the
     * revision effective on the planned start's project day. Without a request, that effective revision is picked, or
     * none for an undated order or a product without approved revisions.
     */
    String resolve(WorkOrder order, String requestedBomId, OffsetDateTime plannedStartAt, boolean approving) {
        if (requestedBomId != null) {
            var bom = references.bom(requestedBomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "bomId does not exist."));
            if (!order.getProjectId().equals(bom.projectId()) || !Objects.equals(order.getTargetItemId(), bom.targetItemId())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "bomId must produce targetItemId in this project.");
            }
            if (!BomStatus.APPROVED.code().equals(bom.bomStatus())) {
                if (approving) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "BOM " + bom.bomName() + " v" + bom.bomVersion() + " is " + bom.bomStatus()
                            + "; approve it or pick an approved revision before approving the work order.");
                }
                // A draft order may name a revision still under review; approving the order needs it approved.
                return requestedBomId;
            }
        }
        if (plannedStartAt == null || order.getTargetItemId() == null) {
            return requestedBomId;
        }
        Optional<EffectiveBomView> effective;
        try {
            effective = revisions.findForPlanning(order.getProjectId(), order.getTargetItemId(),
                calendar.date(order.getProjectId(), plannedStartAt.toInstant()));
        } catch (BusinessException gap) {
            // No approved period covers the day: name the requested revision when there is one.
            if (requestedBomId != null && gap.getErrorCode() == ErrorCode.BAD_REQUEST) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, NOT_COVERED);
            }
            throw gap;
        }
        if (requestedBomId == null) {
            return effective.map(EffectiveBomView::bomId).orElse(null);
        }
        if (effective.isEmpty() || !requestedBomId.equals(effective.get().bomId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, NOT_COVERED);
        }
        return requestedBomId;
    }

    /** An approved order keeps its revision: a new planned start must still fall in that revision's period. */
    void requireSameRevision(WorkOrder order, OffsetDateTime newStart) {
        String selected;
        try {
            selected = resolve(order, order.getBomId(), newStart, true);
        } catch (BusinessException error) {
            if (error.getErrorCode() == ErrorCode.BAD_REQUEST) {
                throw new BusinessException(ErrorCode.CONFLICT,
                    "plannedStartAt would invalidate bomId; cancel this order and create a new dated draft to select another revision.");
            }
            throw error;
        }
        if (!Objects.equals(selected, order.getBomId())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "plannedStartAt would change bomId; cancel this order and create a new dated draft to select another revision.");
        }
    }
}
