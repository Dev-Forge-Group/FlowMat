package org.myweb.flowmat.domain.production.application;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.request.WorkOrderRescheduleRequest;
import org.myweb.flowmat.domain.production.api.dto.response.WorkOrderRescheduleResponse;
import org.myweb.flowmat.domain.production.api.dto.response.WorkOrderRescheduleResult;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrder;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrderReschedule;
import org.myweb.flowmat.domain.production.domain.enums.WorkOrderStatus;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.domain.production.repository.WorkOrderRepository;
import org.myweb.flowmat.domain.production.repository.WorkOrderRescheduleRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkOrderRescheduleService {
    private final WorkOrderRepository orders;
    private final WorkOrderRescheduleRepository history;
    private final ProductionRunRepository runs;
    private final ProjectAccessService access;
    private final IdGenerator ids;
    private final WorkOrderBomSelection bomSelection;
    private final WorkOrderOptionalItems optionalItems;

    @Transactional(readOnly = true)
    public List<WorkOrderRescheduleResponse> history(String workOrderId) {
        WorkOrder order = orders.findByWorkOrderIdAndDeletedYn(workOrderId, "N")
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        access.requireProjectReadAccess(order.getProjectId());
        return history.findAllByWorkOrderIdOrderByChangedAtDescChangeIdDesc(workOrderId).stream()
            .map(WorkOrderRescheduleResponse::from).toList();
    }

    @Transactional
    public WorkOrderRescheduleResult reschedule(String workOrderId, WorkOrderRescheduleRequest request) {
        // The same row lock serializes approval, execution start and all other order commands.
        WorkOrder order = orders.findForUpdate(workOrderId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        access.requireProjectOwnerAccess(order.getProjectId());
        String actor = access.requireCurrentUserId();
        String reason = ProductionText.trimToNull(request.reason(), "reason");
        if (reason == null || reason.codePointCount(0, reason.length()) > 1000) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "reason is required and must be 1000 characters or fewer.");
        }
        if (request.requestId() == null) throw new BusinessException(ErrorCode.BAD_REQUEST, "requestId is required.");
        OffsetDateTime start = stored(request.plannedStartAt());
        OffsetDateTime end = stored(request.plannedEndAt());
        OffsetDateTime expectedStart = stored(request.expectedPlannedStartAt());
        OffsetDateTime expectedEnd = stored(request.expectedPlannedEndAt());
        WorkOrderReschedule replay = history.findByWorkOrderIdAndRequestId(workOrderId, request.requestId()).orElse(null);
        if (replay != null) {
            if (!same(replay.getPreviousPlannedStartAt(), expectedStart) || !same(replay.getPreviousPlannedEndAt(), expectedEnd)
                || !same(replay.getPlannedStartAt(), start) || !same(replay.getPlannedEndAt(), end)
                || !reason.equals(replay.getReason()) || !actor.equals(replay.getChangedBy())) {
                throw new BusinessException(ErrorCode.CONFLICT, "requestId already belongs to a different schedule command.");
            }
            return result(order, replay);
        }
        WorkOrderStatus state = WorkOrderServiceImpl.status(order);
        if (state != WorkOrderStatus.APPROVED && state != WorkOrderStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.CONFLICT, "Only approved or in_progress work orders can be rescheduled. Edit drafts normally.");
        }
        if (!same(order.getPlannedStartAt(), expectedStart) || !same(order.getPlannedEndAt(), expectedEnd)) {
            throw new BusinessException(ErrorCode.CONFLICT, "The planned dates changed; reload the order before rescheduling.");
        }
        if ((state == WorkOrderStatus.IN_PROGRESS || order.getActualStartAt() != null)
            && !same(order.getPlannedStartAt(), start)) {
            throw new BusinessException(ErrorCode.CONFLICT, "plannedStartAt cannot change after execution has started.");
        }
        if (start != null && end != null && end.isBefore(start)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "plannedEndAt must not be before plannedStartAt.");
        }
        if (same(order.getPlannedStartAt(), start) && same(order.getPlannedEndAt(), end)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "The planned dates are unchanged.");
        }
        if (!same(order.getPlannedStartAt(), start)) bomSelection.requireSameRevision(order, start);
        WorkOrderReschedule change = new WorkOrderReschedule();
        change.setChangeId(ids.generate()); change.setWorkOrderId(workOrderId); change.setRequestId(request.requestId());
        change.setPreviousPlannedStartAt(order.getPlannedStartAt()); change.setPreviousPlannedEndAt(order.getPlannedEndAt());
        change.setPlannedStartAt(start); change.setPlannedEndAt(end); change.setReason(reason);
        change.setChangedBy(actor); change.setChangedAt(OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS));
        order.setPlannedStartAt(start); order.setPlannedEndAt(end); order.setUpdatedBy(actor);
        orders.save(order); history.save(change);
        return result(order, change);
    }

    private WorkOrderRescheduleResult result(WorkOrder order, WorkOrderReschedule change) {
        return new WorkOrderRescheduleResult(WorkOrderServiceImpl.toResponse(order,
            runs.findAllByWorkOrderIdInAndDeletedYn(List.of(order.getWorkOrderId()), "N"), optionalItems.of(order.getWorkOrderId())),
            WorkOrderRescheduleResponse.from(change));
    }
    private static OffsetDateTime stored(OffsetDateTime date) {
        if (date == null) return null;
        if (date.getYear() < 1 || date.getYear() > 9999) throw new BusinessException(ErrorCode.BAD_REQUEST, "Planned dates must have a year from 1 to 9999.");
        return date.truncatedTo(ChronoUnit.MICROS);
    }
    private static boolean same(OffsetDateTime left, OffsetDateTime right) {
        return left == null ? right == null : right != null && left.isEqual(right);
    }
}
