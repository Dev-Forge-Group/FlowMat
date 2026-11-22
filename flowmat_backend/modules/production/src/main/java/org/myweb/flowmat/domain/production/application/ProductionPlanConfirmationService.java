package org.myweb.flowmat.domain.production.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.production.api.dto.request.PlanConfirmRequest;
import org.myweb.flowmat.domain.production.api.dto.request.PlanPreviewRequest;
import org.myweb.flowmat.domain.production.api.dto.request.WorkOrderCreateRequest;
import org.myweb.flowmat.domain.production.api.dto.response.PlanConfirmationResponse;
import org.myweb.flowmat.domain.production.api.dto.response.PlanPreviewResponse;
import org.myweb.flowmat.domain.production.domain.entity.PlanConfirmation;
import org.myweb.flowmat.domain.production.repository.PlanConfirmationRepository;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.domain.purchasing.application.publicapi.PurchaseDraftCommand;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowAccess;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowAccessView;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowProductionQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a plan seen before into a draft work order and a draft purchase order for its shortages, in one transaction
 * (docs/architecture/adr/ADR-043-plan-backwards-from-a-target.md decision 7). The plan is worked out again first: when the
 * published revision or an item's usable stock differs from the plan's basis, nothing is made and 409 says what changed.
 * The same requestId answers the same again. Stock does not move: it moves only when a production run takes and makes.
 */
@Service
@RequiredArgsConstructor
public class ProductionPlanConfirmationService {

    private final WorkflowAccess workflowAccess;
    private final WorkflowProductionQuery revisions;
    private final ProductionPlanService plans;
    private final WorkOrderService workOrders;
    private final PurchaseDraftCommand purchaseDrafts;
    private final PlanConfirmationRepository confirmations;
    private final org.myweb.flowmat.domain.production.repository.WorkOrderRepository workOrderRows;
    private final CatalogQuery catalogQuery;
    private final ProjectAccess projectAccess;

    @Transactional
    public PlanConfirmationResponse confirm(String workflowId, PlanConfirmRequest request) {
        // The workflow's write lock, which publishing takes too: no revision is published while this checks and makes.
        WorkflowAccessView workflow = workflowAccess.requireWriteAndLock(workflowId);
        String requestId = ProductionRunServiceImpl.requestKey(request.requestId());
        PlanConfirmation earlier = confirmations.findById(new PlanConfirmation.Key(workflow.workflowId(), requestId)).orElse(null);
        if (earlier != null) {
            if (!sameTarget(earlier, request.target())) {
                throw new BusinessException(ErrorCode.CONFLICT, "requestId already belongs to a different confirmation.");
            }
            return response(earlier);
        }

        String latest = revisions.latestPublishedRevision(workflow.workflowId())
            .map(WorkflowProductionQuery.Revision::workflowRevisionId).orElse(null);
        if (!Objects.equals(latest, request.basis().revisionId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "The workflow has a newer published revision; plan again before confirming.");
        }
        PlanPreviewResponse plan = plans.preview(workflow.workflowId(),
            new PlanPreviewRequest(request.basis().revisionId(), request.target(), request.exclusiveChoices()));
        List<String> changed = changedStock(request.basis().available(), plan.basis().available());
        if (!changed.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Usable stock changed since the plan for " + String.join(", ", changed)
                + "; plan again before confirming.");
        }
        if (!plan.feasible()) {
            throw new BusinessException(ErrorCode.CONFLICT, "The plan cannot bring the target: " + plan.limits().stream()
                .filter(limit -> !"stock".equals(limit.kind()) && !"equipment-time".equals(limit.kind()))
                .map(PlanPreviewResponse.Limit::message).findFirst().orElse("see its limits"));
        }
        List<PurchaseDraftCommand.Line> shortages = plan.requirements().stream()
            .filter(row -> row.itemId() != null && row.shortage() != null && row.shortage().signum() > 0)
            .map(row -> new PurchaseDraftCommand.Line(row.itemId(), inPurchaseUnits(row.itemId(), row.shortage()))).toList();
        if (!shortages.isEmpty() && (request.supplierName() == null || request.supplierName().isBlank())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Name the supplier for the draft purchase order of the shortages.");
        }

        String targetItem = plans.targetItemOf(workflow.workflowId(), plan.revisionId(), request.target());
        String title = "Planned " + plain(plan.target()) + (plan.targetUnit() == null ? "" : " " + plan.targetUnit())
            + (targetItem == null ? "" : " of " + itemName(targetItem));
        String workOrderId = workOrders.createWorkOrder(new WorkOrderCreateRequest(workflow.projectId(), title, workflow.workflowId(),
            null, targetItem, plan.target(), null, null, null, "From the plan of revision " + plan.revisionId() + ".", null, null))
            .workOrderId();
        workOrderRows.findById(workOrderId).ifPresent(order -> order.setWorkflowRevisionId(plan.revisionId()));
        String purchaseOrderId = shortages.isEmpty() ? null
            : purchaseDrafts.draft(workflow.projectId(), request.supplierName(), "For the planned work order " + title, shortages);

        PlanConfirmation confirmation = new PlanConfirmation();
        confirmation.setWorkflowId(workflow.workflowId());
        confirmation.setRequestId(requestId);
        confirmation.setProjectId(workflow.projectId());
        confirmation.setRevisionId(plan.revisionId());
        confirmation.setTargetPortId(request.target().processIoId());
        confirmation.setTargetConnectionId(request.target().connectionId());
        confirmation.setTargetQuantity(request.target().quantity());
        confirmation.setWorkOrderId(workOrderId);
        confirmation.setPurchaseOrderId(purchaseOrderId);
        confirmation.setCreatedBy(projectAccess.requireCurrentUserId());
        confirmation.setCreatedAt(OffsetDateTime.now());
        return response(confirmations.saveAndFlush(confirmation));
    }

    /** Items whose usable stock is not what the plan read, by id. */
    private static List<String> changedStock(Map<String, BigDecimal> before, Map<String, BigDecimal> now) {
        Set<String> items = new TreeSet<>();
        if (before != null) items.addAll(before.keySet());
        items.addAll(now.keySet());
        List<String> changed = new ArrayList<>();
        for (String item : items) {
            BigDecimal was = before == null ? null : before.get(item);
            BigDecimal is = now.get(item);
            if (was == null || is == null || was.compareTo(is) != 0) changed.add(item);
        }
        return changed;
    }

    private static boolean sameTarget(PlanConfirmation earlier, PlanPreviewRequest.Target target) {
        return Objects.equals(earlier.getTargetPortId(), target.processIoId())
            && Objects.equals(earlier.getTargetConnectionId(), target.connectionId())
            && earlier.getTargetQuantity().compareTo(target.quantity()) == 0;
    }

    /** The shortage rounded up to whole purchase units when the item has one, as the screen's draft does (PO12). */
    private BigDecimal inPurchaseUnits(String itemId, BigDecimal shortage) {
        CatalogItemView item = catalogQuery.findItems(Set.of(itemId)).get(itemId);
        if (item == null || item.purchaseUnit() == null || item.conversionRate() == null || item.conversionRate().signum() <= 0) {
            return shortage;
        }
        BigDecimal packs = shortage.divide(item.conversionRate(), 0, java.math.RoundingMode.CEILING);
        return packs.multiply(item.conversionRate());
    }

    private String itemName(String itemId) {
        CatalogItemView item = catalogQuery.findItems(Set.of(itemId)).get(itemId);
        return item == null ? itemId : item.itemName();
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static PlanConfirmationResponse response(PlanConfirmation confirmation) {
        return new PlanConfirmationResponse(confirmation.getRequestId(), confirmation.getRevisionId(), confirmation.getWorkOrderId(),
            confirmation.getPurchaseOrderId() == null ? List.of() : List.of(confirmation.getPurchaseOrderId()));
    }
}
