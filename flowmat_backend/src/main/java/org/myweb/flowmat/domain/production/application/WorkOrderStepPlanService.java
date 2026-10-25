package org.myweb.flowmat.domain.production.application;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.request.WorkOrderStepPlanRequest;
import org.myweb.flowmat.domain.production.api.dto.response.WorkOrderLaborComparisonResponse;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrder;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrderStepPlan;
import org.myweb.flowmat.domain.production.domain.enums.WorkOrderStatus;
import org.myweb.flowmat.domain.production.repository.WorkOrderRepository;
import org.myweb.flowmat.domain.production.repository.WorkOrderStepPlanRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.workflow.application.publicapi.ProcessRunView;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowRunQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A work order's planned worker minutes by process (docs/architecture/adr/ADR-023-labor-step-followups.md decision 5,
 * docs/domain/worker-calendar.md LB23): processes of its own workflow, each once, sent whole while the order is open.
 * It answers with the comparison it stands in (LB6, LB18).
 */
@Service
@RequiredArgsConstructor
public class WorkOrderStepPlanService {
    static final int MAX_STEPS = 50;
    static final int MAX_MINUTES = 100_000;

    private final WorkOrderRepository workOrders;
    private final WorkOrderStepPlanRepository stepPlans;
    private final WorkflowRunQuery workflowRuns;
    private final WorkOrderLaborComparisonService comparison;
    private final ProjectAccessService access;
    private final IdGenerator ids;

    @Transactional
    public WorkOrderLaborComparisonResponse set(String workOrderId, WorkOrderStepPlanRequest request) {
        WorkOrder order = workOrders.findForUpdate(workOrderId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        access.requireProjectWriteAccess(order.getProjectId());
        WorkOrderStatus status = WorkOrderServiceImpl.status(order);
        if (status == WorkOrderStatus.COMPLETED || status == WorkOrderStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.CONFLICT,
                order.getWorkOrderNumber() + " is " + order.getWorkOrderStatus() + "; its step plan can no longer change.");
        }
        if (order.getWorkflowId() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, order.getWorkOrderNumber() + " names no workflow, so it has no steps to plan.");
        }
        List<WorkOrderStepPlanRequest.Step> asked = request == null || request.steps() == null ? List.of() : request.steps();
        if (asked.size() > MAX_STEPS) {
            throw bad("A step plan has at most " + MAX_STEPS + " steps.");
        }
        String actor = access.requireCurrentUserId();
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        Set<String> seen = new HashSet<>();
        List<WorkOrderStepPlan> wanted = new ArrayList<>();
        for (int index = 0; index < asked.size(); index++) {
            WorkOrderStepPlanRequest.Step step = asked.get(index);
            if (step == null) {
                throw bad("steps must not contain null entries.");
            }
            String nodeId = step.nodeId() == null || step.nodeId().isBlank() ? null : step.nodeId().trim();
            if (nodeId == null) {
                throw bad("Name each step by its process id.");
            }
            if (step.plannedMinutes() == null || step.plannedMinutes() < 1 || step.plannedMinutes() > MAX_MINUTES) {
                throw bad("Step " + (index + 1) + ": planned minutes are whole minutes from 1 to " + MAX_MINUTES + ".");
            }
            ProcessRunView process = workflowRuns.findProcess(nodeId)
                .filter(one -> order.getWorkflowId().equals(one.workflowId()) && order.getProjectId().equals(one.projectId()))
                .orElseThrow(() -> bad(nodeId + " is not a process of " + order.getWorkOrderNumber() + "'s workflow."));
            if (!seen.add(nodeId)) {
                throw bad("Plan each process once.");
            }
            WorkOrderStepPlan row = new WorkOrderStepPlan();
            row.setPlanId(ids.generate());
            row.setWorkOrderId(order.getWorkOrderId());
            row.setNodeId(nodeId);
            row.setNodeName(process.processName());
            row.setSortOrder(index);
            row.setPlannedMinutes(step.plannedMinutes());
            row.setUpdatedBy(actor);
            row.setUpdatedAt(now);
            wanted.add(row);
        }
        stepPlans.deleteAll(stepPlans.findAllByWorkOrderIdOrderBySortOrderAsc(order.getWorkOrderId()));
        // The old plan goes first, so the new one can take its processes.
        stepPlans.flush();
        stepPlans.saveAllAndFlush(wanted);
        return comparison.compare(order.getWorkOrderId());
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
