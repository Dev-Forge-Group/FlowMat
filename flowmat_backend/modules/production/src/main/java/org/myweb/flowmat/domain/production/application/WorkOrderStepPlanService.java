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
import org.myweb.flowmat.domain.production.domain.entity.WorkOrderStepPlanPerson;
import org.myweb.flowmat.domain.production.domain.enums.WorkOrderStatus;
import org.myweb.flowmat.domain.production.repository.WorkOrderRepository;
import org.myweb.flowmat.domain.production.repository.WorkOrderStepPlanPersonRepository;
import org.myweb.flowmat.domain.production.repository.WorkOrderStepPlanRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectMemberQuery;
import org.myweb.flowmat.domain.workflow.application.publicapi.ProcessRunView;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowRunQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A work order's planned worker minutes by process (docs/architecture/adr/ADR-023-labor-step-followups.md decision 5,
 * docs/domain/worker-calendar.md LB23): processes of its own workflow, each once, sent whole while the order is open. A process
 * may split its minutes among people of the project (decision 11, LB29). It answers with the comparison it stands in (LB6, LB18).
 */
@Service
@RequiredArgsConstructor
public class WorkOrderStepPlanService {
    static final int MAX_STEPS = 50;
    static final int MAX_MINUTES = 100_000;
    static final int MAX_PEOPLE = 50;

    private final WorkOrderRepository workOrders;
    private final WorkOrderStepPlanRepository stepPlans;
    private final WorkOrderStepPlanPersonRepository stepPlanPeople;
    private final ProjectMemberQuery members;
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
        List<WorkOrderStepPlanPerson> wantedPeople = new ArrayList<>();
        for (int index = 0; index < asked.size(); index++) {
            WorkOrderStepPlanRequest.Step step = asked.get(index);
            if (step == null) {
                throw bad("steps must not contain null entries.");
            }
            String nodeId = step.nodeId() == null || step.nodeId().isBlank() ? null : step.nodeId().trim();
            if (nodeId == null) {
                throw bad("Name each step by its process id.");
            }
            List<WorkOrderStepPlanRequest.Person> split = step.people() == null ? List.of() : step.people();
            int planned;
            if (split.isEmpty()) {
                if (step.plannedMinutes() == null || step.plannedMinutes() < 1 || step.plannedMinutes() > MAX_MINUTES) {
                    throw bad("Step " + (index + 1) + ": planned minutes are whole minutes from 1 to " + MAX_MINUTES + ".");
                }
                planned = step.plannedMinutes();
            } else {
                planned = splitMinutes(order, index + 1, split);
                if (step.plannedMinutes() != null && step.plannedMinutes() != planned) {
                    throw bad("Step " + (index + 1) + ": the people's minutes add up to " + planned + ", not " + step.plannedMinutes()
                        + "; leave the step's minutes out or make them agree.");
                }
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
            row.setPlannedMinutes(planned);
            row.setUpdatedBy(actor);
            row.setUpdatedAt(now);
            wanted.add(row);
            for (int at = 0; at < split.size(); at++) {
                WorkOrderStepPlanPerson person = new WorkOrderStepPlanPerson();
                person.setPlanPersonId(ids.generate());
                person.setPlanId(row.getPlanId());
                person.setUserId(split.get(at).userId().trim());
                person.setSortOrder(at);
                person.setPlannedMinutes(split.get(at).plannedMinutes());
                wantedPeople.add(person);
            }
        }
        stepPlans.deleteAll(stepPlans.findAllByWorkOrderIdOrderBySortOrderAsc(order.getWorkOrderId()));
        // The old plan goes first, its people with it, so the new one can take its processes.
        stepPlans.flush();
        stepPlans.saveAllAndFlush(wanted);
        stepPlanPeople.saveAllAndFlush(wantedPeople);
        return comparison.compare(order.getWorkOrderId());
    }

    /** The people a process's minutes are split among (LB29): members of the project, each once; their minutes added up. */
    private int splitMinutes(WorkOrder order, int number, List<WorkOrderStepPlanRequest.Person> split) {
        if (split.size() > MAX_PEOPLE) {
            throw bad("Step " + number + ": split it among at most " + MAX_PEOPLE + " people.");
        }
        Set<String> seen = new HashSet<>();
        long total = 0;
        for (WorkOrderStepPlanRequest.Person person : split) {
            String userId = person == null || person.userId() == null || person.userId().isBlank() ? null : person.userId().trim();
            if (userId == null) {
                throw bad("Step " + number + ": name each person by their user id.");
            }
            if (person.plannedMinutes() == null || person.plannedMinutes() < 1 || person.plannedMinutes() > MAX_MINUTES) {
                throw bad("Step " + number + ": a person's planned minutes are whole minutes from 1 to " + MAX_MINUTES + ".");
            }
            if (!seen.add(userId)) {
                throw bad("Step " + number + ": plan each person once.");
            }
            if (!members.canRead(order.getProjectId(), userId)) {
                throw bad("Step " + number + ": " + userId + " is not in this project.");
            }
            total += person.plannedMinutes();
        }
        if (total > MAX_MINUTES) {
            throw bad("Step " + number + ": the people's minutes add up to more than " + MAX_MINUTES + ".");
        }
        return (int) total;
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
