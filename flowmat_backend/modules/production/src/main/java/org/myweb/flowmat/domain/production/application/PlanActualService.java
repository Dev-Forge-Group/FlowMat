package org.myweb.flowmat.domain.production.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.catalog.application.publicapi.UnitConversion;
import org.myweb.flowmat.domain.flowrun.application.publicapi.LinkedRunStep;
import org.myweb.flowmat.domain.flowrun.application.publicapi.LinkedRunSteps;
import org.myweb.flowmat.domain.production.api.dto.request.PlanPreviewRequest;
import org.myweb.flowmat.domain.production.api.dto.response.PlanActualResponse;
import org.myweb.flowmat.domain.production.api.dto.response.PlanConfirmationSummaryResponse;
import org.myweb.flowmat.domain.production.api.dto.response.PlanPreviewResponse;
import org.myweb.flowmat.domain.production.domain.entity.PlanConfirmation;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRunItem;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrder;
import org.myweb.flowmat.domain.production.repository.PlanConfirmationRepository;
import org.myweb.flowmat.domain.production.repository.ProductionRunItemRepository;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.domain.production.repository.WorkOrderRepository;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowAccess;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A confirmed plan against its work order's finished runs (docs/domain/plan-vs-actual.md, ROADMAP-2026-11-23.md §5 4). The
 * plan is made again from the confirmation's revision and target, which fix the needs and step minutes; stock does not
 * change them. Only finished runs count, so a run half done does not look like a saving. Read only.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlanActualService {

    /** The pilot targets (ROADMAP §5 4): material within 5%, time within 15%. Decision 2 (판정 2) may change them. */
    static final BigDecimal MATERIAL_TOLERANCE = new BigDecimal("5");
    static final BigDecimal TIME_TOLERANCE = new BigDecimal("15");

    private static final int SCALE = 4;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal SECONDS_PER_MINUTE = new BigDecimal("60");

    private final WorkOrderRepository workOrders;
    private final PlanConfirmationRepository confirmations;
    private final ProductionRunRepository runs;
    private final ProductionRunItemRepository runItems;
    private final LinkedRunSteps runSteps;
    private final ProductionPlanService plans;
    private final CatalogQuery catalogQuery;
    private final UnitConversion unitConverter;
    private final ProjectAccess projectAccess;
    private final WorkflowAccess workflowAccess;

    public List<PlanConfirmationSummaryResponse> confirmations(String workflowId) {
        workflowAccess.requireRead(workflowId);
        return confirmations.findAllByWorkflowIdOrderByCreatedAtDesc(workflowId).stream()
            .map(row -> new PlanConfirmationSummaryResponse(row.getRequestId(), row.getRevisionId(), row.getTargetPortId(),
                row.getTargetConnectionId(), row.getTargetQuantity(), row.getWorkOrderId(),
                row.getPurchaseOrderId() == null ? List.of() : List.of(row.getPurchaseOrderId()), row.getCreatedAt()))
            .toList();
    }

    public PlanActualResponse compare(String workOrderId) {
        WorkOrder order = workOrders.findByWorkOrderIdAndDeletedYn(workOrderId, "N")
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        projectAccess.requireProjectReadAccess(order.getProjectId());
        PlanConfirmation confirmation = confirmations.findFirstByWorkOrderId(workOrderId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "This work order did not come from a confirmed plan."));

        List<String> notes = new ArrayList<>();
        boolean complete = true;
        PlanPreviewResponse plan = null;
        try {
            plan = plans.preview(confirmation.getWorkflowId(), new PlanPreviewRequest(confirmation.getRevisionId(),
                new PlanPreviewRequest.Target(confirmation.getTargetPortId(), confirmation.getTargetConnectionId(),
                    confirmation.getTargetQuantity(), null), null));
        } catch (BusinessException e) {
            complete = false;
            notes.add("The plan could not be made again: " + e.getMessage());
        }
        BigDecimal target = plan == null ? confirmation.getTargetQuantity() : plan.target();

        List<ProductionRun> finished = new ArrayList<>();
        int open = 0;
        for (ProductionRun run : runs.findAllByWorkOrderIdInAndDeletedYn(List.of(workOrderId), "N")) {
            if ("finished".equalsIgnoreCase(run.getRunStatus())) {
                finished.add(run);
            } else if (!"cancelled".equalsIgnoreCase(run.getRunStatus())) {
                open++;
            }
        }
        BigDecimal output = finished.stream().map(ProductionRun::getActualOutputQty).filter(java.util.Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        // The plan for what was made: a part made is compared with the plan for that part.
        BigDecimal share = target == null || target.signum() <= 0 || output.signum() <= 0 ? null
            : output.divide(target, SCALE + 6, RoundingMode.HALF_UP);

        // Used: inputs not cancelled with an actual quantity, in the item's own unit, as a run's material use counts them.
        Map<String, BigDecimal> used = new HashMap<>();
        Set<String> unconvertible = new HashSet<>();
        Map<String, CatalogItemView> items = new HashMap<>();
        for (ProductionRun run : finished) {
            for (ProductionRunItem recording : runItems.findAllByProductionRunIdOrderByProductionRunItemIdAsc(run.getProductionRunId())) {
                if (!"input".equals(recording.getDirection()) || recording.isCancelled() || recording.getActualQty() == null) {
                    continue;
                }
                CatalogItemView item = items.computeIfAbsent(recording.getItemId(),
                    id -> catalogQuery.findItems(List.of(id)).get(id));
                if (item == null) {
                    continue;
                }
                try {
                    used.merge(item.itemId(), unitConverter.toItemUnit(recording.getActualQty(), recording.getUnit(), item.unitId())
                        .quantity(), BigDecimal::add);
                } catch (BusinessException e) {
                    unconvertible.add(item.itemId());
                }
            }
        }

        Map<String, PlanActualResponse.Material> materials = new LinkedHashMap<>();
        if (plan != null) {
            for (PlanPreviewResponse.Requirement row : plan.requirements()) {
                if (row.itemId() == null) {
                    continue;
                }
                materials.put(row.itemId(), material(row.itemId(), row.itemName(), row.unit(), row.needed(), share,
                    used.get(row.itemId()), unconvertible.contains(row.itemId()), MATERIAL_TOLERANCE));
            }
        }
        // What the runs used beyond the plan: nothing planned, so no percent.
        for (Map.Entry<String, BigDecimal> entry : used.entrySet()) {
            if (!materials.containsKey(entry.getKey())) {
                CatalogItemView item = items.get(entry.getKey());
                materials.put(entry.getKey(), new PlanActualResponse.Material(item.itemId(), item.itemName(), item.unitCode(),
                    scale(BigDecimal.ZERO), scale(BigDecimal.ZERO), scale(entry.getValue()), null, null));
            }
        }
        for (String itemId : unconvertible) {
            complete = false;
            CatalogItemView item = items.get(itemId);
            notes.add("A recording of " + (item == null ? itemId : item.itemName()) + " is in a unit that does not convert to "
                + (item == null ? "its unit" : item.unitCode()) + ".");
            if (!materials.containsKey(itemId) && item != null) {
                materials.put(itemId, new PlanActualResponse.Material(itemId, item.itemName(), item.unitCode(), scale(BigDecimal.ZERO),
                    scale(BigDecimal.ZERO), null, null, null));
            }
        }

        List<PlanActualResponse.Step> steps = steps(plan, finished, share);
        if (finished.isEmpty()) {
            notes.add("No run of this work order has finished yet.");
        }
        if (open > 0) {
            notes.add(open + " run(s) not finished are left out.");
        }
        return new PlanActualResponse(order.getWorkOrderId(), order.getWorkOrderStatus(), confirmation.getWorkflowId(),
            confirmation.getRevisionId(), confirmation.getCreatedAt(), target, plan == null ? null : plan.targetUnit(), scale(output),
            finished.size(), open, MATERIAL_TOLERANCE, TIME_TOLERANCE, List.copyOf(materials.values()), steps, complete,
            List.copyOf(notes));
    }

    /** Step minutes by process: the plan's and the finished runs' own, from each step's start to its end. */
    private List<PlanActualResponse.Step> steps(PlanPreviewResponse plan, List<ProductionRun> finished, BigDecimal share) {
        Map<String, BigDecimal> actual = new LinkedHashMap<>();
        Map<String, Integer> measured = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        for (ProductionRun run : finished) {
            for (LinkedRunStep step : runSteps.steps(run.getProductionRunId())) {
                if (step.gateway() || step.startedAt() == null || step.endedAt() == null || step.nodeId() == null) {
                    continue;
                }
                BigDecimal minutes = BigDecimal.valueOf(Duration.between(step.startedAt(), step.endedAt()).toMillis())
                    .divide(BigDecimal.valueOf(1000).multiply(SECONDS_PER_MINUTE), SCALE + 4, RoundingMode.HALF_UP);
                actual.merge(step.nodeId(), minutes, BigDecimal::add);
                measured.merge(step.nodeId(), 1, Integer::sum);
                names.putIfAbsent(step.nodeId(), step.nodeName());
            }
        }
        Map<String, PlanActualResponse.Step> byProcess = new LinkedHashMap<>();
        if (plan != null) {
            for (PlanPreviewResponse.Node node : plan.nodes()) {
                if (node.minutes() == null && !actual.containsKey(node.processId())) {
                    continue;
                }
                BigDecimal planned = node.minutes() == null || share == null ? null : node.minutes().multiply(share);
                BigDecimal done = actual.get(node.processId());
                byProcess.put(node.processId(), new PlanActualResponse.Step(node.processId(), names.get(node.processId()),
                    node.minutes() == null ? null : scale(node.minutes()), planned == null ? null : scale(planned),
                    done == null ? null : scale(done), measured.getOrDefault(node.processId(), 0),
                    variance(planned, done), within(variance(planned, done), TIME_TOLERANCE)));
            }
        }
        actual.forEach((processId, minutes) -> byProcess.computeIfAbsent(processId, id -> new PlanActualResponse.Step(id,
            names.get(id), null, null, scale(minutes), measured.get(id), null, null)));
        return List.copyOf(byProcess.values());
    }

    private static PlanActualResponse.Material material(String itemId, String itemName, String unit, BigDecimal forTarget,
        BigDecimal share, BigDecimal used, boolean unconvertible, BigDecimal tolerance) {
        BigDecimal planned = share == null ? null : forTarget.multiply(share);
        BigDecimal actual = unconvertible ? null : used == null ? (share == null ? null : BigDecimal.ZERO) : used;
        BigDecimal variance = variance(planned, actual);
        return new PlanActualResponse.Material(itemId, itemName, unit, scale(forTarget), planned == null ? null : scale(planned),
            actual == null ? null : scale(actual), variance, within(variance, tolerance));
    }

    /** In percent with one decimal; null when there is no plan to compare with. */
    static BigDecimal variance(BigDecimal planned, BigDecimal actual) {
        if (planned == null || actual == null || planned.signum() <= 0) {
            return null;
        }
        return actual.subtract(planned).multiply(HUNDRED).divide(planned, 1, RoundingMode.HALF_UP);
    }

    private static Boolean within(BigDecimal variance, BigDecimal tolerance) {
        return variance == null ? null : variance.abs().compareTo(tolerance) <= 0;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
