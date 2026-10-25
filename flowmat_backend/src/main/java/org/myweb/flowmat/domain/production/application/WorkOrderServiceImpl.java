package org.myweb.flowmat.domain.production.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.bom.application.publicapi.BomRevisionView;
import org.myweb.flowmat.domain.bom.domain.enums.BomStatus;
import org.myweb.flowmat.domain.catalog.application.ItemStatusRule;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.production.api.dto.request.WorkOrderCreateRequest;
import org.myweb.flowmat.domain.production.api.dto.request.WorkOrderUpdateRequest;
import org.myweb.flowmat.domain.production.api.dto.response.WorkOrderResponse;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrder;
import org.myweb.flowmat.domain.production.domain.enums.WorkOrderStatus;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.domain.production.repository.WorkOrderRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowRunView;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Work orders follow {@link WorkOrderStatus}: editors create and edit drafts and complete running orders,
 * project owners approve or cancel them. The approved → in_progress step happens when the first production
 * actual run is started against the order (see ProductionRunServiceImpl).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkOrderServiceImpl implements WorkOrderService {

    private static final String NOT_DELETED = "N";
    private static final String FINISHED_RUN = "finished";
    private static final BigDecimal MIN_ROUNDABLE_TARGET = new BigDecimal("0.00005");
    private static final BigDecimal TARGET_UPPER_BOUND = new BigDecimal("10000000000");
    private static final BigDecimal MAX_TARGET_QUANTITY = new BigDecimal("9999999999.9999");
    private static final Set<String> PRIORITIES = Set.of("low", "normal", "high", "urgent");
    private static final DateTimeFormatter NUMBER_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final WorkOrderRepository workOrderRepository;
    private final ProductionRunRepository productionRunRepository;
    private final ProductionPlanningReferences references;
    private final WorkOrderBomSelection bomSelection;
    private final ProjectAccessService projectAccessService;
    private final IdGenerator idGenerator;
    private final StockAllocationService stockAllocationService;
    private final org.myweb.flowmat.domain.notification.application.publicapi.NotificationCommand notifications;
    private final org.myweb.flowmat.domain.project.application.publicapi.ProjectMemberQuery projectMemberQuery;

    @Override
    public List<WorkOrderResponse> listWorkOrders(String projectId) {
        projectAccessService.requireProjectReadAccess(projectId);
        List<WorkOrder> orders = workOrderRepository.findAllByProjectIdAndDeletedYnOrderByCreatedAtDesc(projectId, NOT_DELETED);
        Map<String, List<ProductionRun>> runsByOrder = runsByWorkOrder(orders.stream().map(WorkOrder::getWorkOrderId).toList());
        return orders.stream()
            .map(order -> toResponse(order, runsByOrder.getOrDefault(order.getWorkOrderId(), List.of())))
            .toList();
    }

    @Override
    public WorkOrderResponse getWorkOrder(String workOrderId) {
        WorkOrder order = findActiveOrder(workOrderId);
        projectAccessService.requireProjectReadAccess(order.getProjectId());
        return toResponse(order);
    }

    @Override
    @Transactional
    public WorkOrderResponse createWorkOrder(WorkOrderCreateRequest request) {
        ProductionText.requireStorable(request.projectId(), "projectId");
        String projectId = request.projectId().trim();
        projectAccessService.requireProjectWriteAccess(projectId);

        WorkOrder order = new WorkOrder();
        order.setWorkOrderId(idGenerator.generate());
        order.setProjectId(projectId);
        order.setWorkOrderNumber(generateWorkOrderNumber());
        order.setWorkOrderStatus(WorkOrderStatus.DRAFT.code());
        applyEditableFields(order, request.workOrderTitle(), request.workflowId(), request.targetItemId(),
            request.targetQuantity(), request.priority(), request.plannedStartAt(), request.plannedEndAt(),
            request.instruction(), request.assignedTo(), request.bomId());
        order.setInstructionUrl(instructionUrl(request.instructionUrl()));
        order.setCreatedBy(projectAccessService.requireCurrentUserId());
        order.setDeletedYn(NOT_DELETED);
        WorkOrder saved = workOrderRepository.save(order);
        tellAssignee(saved, null);
        return toResponse(saved, List.of());
    }

    @Override
    @Transactional
    public WorkOrderResponse updateWorkOrder(String workOrderId, WorkOrderUpdateRequest request) {
        WorkOrder order = findOrderForUpdate(workOrderId);
        projectAccessService.requireProjectWriteAccess(order.getProjectId());
        if (status(order) != WorkOrderStatus.DRAFT) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "Only draft work orders can be edited; " + order.getWorkOrderNumber() + " is " + order.getWorkOrderStatus() + ".");
        }
        String assigneeBefore = order.getAssignedTo();
        String title = request.workOrderTitle() != null && !request.workOrderTitle().isBlank()
            ? request.workOrderTitle()
            : order.getWorkOrderTitle();
        applyEditableFields(order, title, request.workflowId(), request.targetItemId(), request.targetQuantity(),
            request.priority(), request.plannedStartAt(), request.plannedEndAt(), request.instruction(), request.assignedTo(),
            request.bomId());
        order.setInstructionUrl(instructionUrl(request.instructionUrl()));
        order.setUpdatedBy(projectAccessService.requireCurrentUserId());
        WorkOrder saved = workOrderRepository.save(order);
        tellAssignee(saved, assigneeBefore);
        return toResponse(saved);
    }

    /**
     * A newly named assignee hears of the order (docs/domain/notification.md NT2) when the name is a person who can read
     * the project; the field also takes free text, such as a crew name, which tells no one.
     */
    private void tellAssignee(WorkOrder order, String before) {
        String assignee = order.getAssignedTo();
        if (assignee == null || assignee.equals(before) || !projectMemberQuery.canRead(order.getProjectId(), assignee)) {
            return;
        }
        notifications.notifyAssignee(assignee, projectAccessService.requireCurrentUserId(), order.getProjectId(), "work_order",
            "Work order " + order.getWorkOrderNumber() + " is yours", order.getWorkOrderTitle(), "work_order", order.getWorkOrderId());
    }

    @Override
    @Transactional
    public WorkOrderResponse approveWorkOrder(String workOrderId) {
        WorkOrder order = findOrderForUpdate(workOrderId);
        projectAccessService.requireProjectOwnerAccess(order.getProjectId());
        order.setBomId(bomSelection.resolve(order, order.getBomId(), order.getPlannedStartAt(), true));
        transition(order, WorkOrderStatus.APPROVED);
        String userId = projectAccessService.requireCurrentUserId();
        OffsetDateTime now = OffsetDateTime.now();
        order.setApprovedBy(userId);
        order.setApprovedAt(now);
        order.setIssuedBy(userId);
        order.setIssuedAt(now);
        return toResponse(workOrderRepository.save(order));
    }

    @Override
    @Transactional
    public WorkOrderResponse cancelWorkOrder(String workOrderId) {
        WorkOrder order = findOrderForUpdate(workOrderId);
        projectAccessService.requireProjectOwnerAccess(order.getProjectId());
        transition(order, WorkOrderStatus.CANCELLED);
        // Stock allocated to the order goes back (docs/domain/stock-allocation.md).
        stockAllocationService.releaseOpenOf(order);
        order.setUpdatedBy(projectAccessService.requireCurrentUserId());
        return toResponse(workOrderRepository.save(order));
    }

    @Override
    @Transactional
    public WorkOrderResponse completeWorkOrder(String workOrderId) {
        WorkOrder order = findOrderForUpdate(workOrderId);
        projectAccessService.requireProjectWriteAccess(order.getProjectId());
        List<ProductionRun> runs = productionRunRepository.findAllByWorkOrderIdInAndDeletedYn(List.of(workOrderId), NOT_DELETED);
        if (runs.stream().anyMatch(run -> run.affectsPhysicalState()
            && !FINISHED_RUN.equalsIgnoreCase(run.getRunStatus()))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Finish every production run of this work order first.");
        }
        transition(order, WorkOrderStatus.COMPLETED);
        stockAllocationService.releaseOpenOf(order);
        order.setActualEndAt(OffsetDateTime.now());
        order.setUpdatedBy(projectAccessService.requireCurrentUserId());
        return toResponse(workOrderRepository.save(order), runs);
    }

    private void applyEditableFields(
        WorkOrder order,
        String title,
        String workflowId,
        String targetItemId,
        BigDecimal targetQuantity,
        String priority,
        OffsetDateTime plannedStartAt,
        OffsetDateTime plannedEndAt,
        String instruction,
        String assignedTo,
        String bomId
    ) {
        String normalizedTitle = ProductionText.trimToNull(title, "workOrderTitle");
        if (normalizedTitle == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "workOrderTitle is required.");
        }
        if (normalizedTitle.codePointCount(0, normalizedTitle.length()) > 100) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "workOrderTitle must be at most 100 characters.");
        }
        order.setWorkOrderTitle(normalizedTitle);

        String normalizedWorkflowId = ProductionText.trimToNull(workflowId, "workflowId");
        if (normalizedWorkflowId != null) {
            WorkflowRunView workflow = references.workflow(normalizedWorkflowId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "Workflow does not exist."));
            requireSameProject(order, workflow.projectId());
        }
        order.setWorkflowId(normalizedWorkflowId);

        String normalizedItemId = ProductionText.trimToNull(targetItemId, "targetItemId");
        if (normalizedItemId != null) {
            CatalogItemView item = references.item(normalizedItemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "Target item does not exist."));
            requireSameProject(order, item.projectId());
            // Only a new target is checked, so an order for an item phased out later can still be edited and finished.
            if (!normalizedItemId.equals(order.getTargetItemId())) {
                ItemStatusRule.requireActive(item.itemCode(), item.itemStatus(), "plan a work order for it");
            }
        }
        order.setTargetItemId(normalizedItemId);

        // The BOM must make the order's product; an order without a product takes the BOM's.
        String normalizedBomId = ProductionText.trimToNull(bomId, "bomId");
        if (normalizedBomId != null) {
            BomRevisionView bom = findBom(order, normalizedBomId);
            if (BomStatus.RETIRED.code().equals(bom.bomStatus())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "BOM " + bom.bomName() + " v" + bom.bomVersion() + " is retired; pick a current revision.");
            }
            if (normalizedItemId == null) {
                order.setTargetItemId(bom.targetItemId());
            } else if (!normalizedItemId.equals(bom.targetItemId())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "The BOM produces a different item than this work order.");
            }
        }
        order.setBomId(normalizedBomId);

        order.setTargetQuantity(storedTargetQuantity(targetQuantity));

        ProductionText.requireStorable(priority, "priority");
        String normalizedPriority = priority == null || priority.isBlank() ? "normal" : priority.trim().toLowerCase(Locale.ROOT);
        if (!PRIORITIES.contains(normalizedPriority)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Priority must be one of low, normal, high, urgent.");
        }
        order.setPriority(normalizedPriority);

        if (plannedStartAt != null && plannedEndAt != null && plannedEndAt.isBefore(plannedStartAt)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Planned end must not be before planned start.");
        }
        order.setBomId(bomSelection.resolve(order, normalizedBomId, plannedStartAt, false));
        order.setPlannedStartAt(plannedStartAt);
        order.setPlannedEndAt(plannedEndAt);
        order.setInstruction(ProductionText.trimToNull(instruction, "instruction"));
        String normalizedAssignee = ProductionText.trimToNull(assignedTo, "assignedTo");
        if (normalizedAssignee != null && normalizedAssignee.codePointCount(0, normalizedAssignee.length()) > 50) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "assignedTo must be 50 characters or fewer.");
        }
        order.setAssignedTo(normalizedAssignee);
    }

    private static BigDecimal storedTargetQuantity(BigDecimal quantity) {
        if (quantity == null) {
            return null;
        }
        // Bound the value before scaling; very small or large exponents must not expand during rounding.
        if (quantity.compareTo(MIN_ROUNDABLE_TARGET) < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "targetQuantity must round to at least 0.0001.");
        }
        if (quantity.compareTo(TARGET_UPPER_BOUND) >= 0) {
            throw targetQuantityTooLarge();
        }
        BigDecimal stored = quantity.setScale(4, RoundingMode.HALF_UP);
        if (stored.compareTo(MAX_TARGET_QUANTITY) > 0) {
            throw targetQuantityTooLarge();
        }
        return stored;
    }

    private static BusinessException targetQuantityTooLarge() {
        return new BusinessException(ErrorCode.BAD_REQUEST, "targetQuantity must not exceed 9999999999.9999.");
    }

    static void transition(WorkOrder order, WorkOrderStatus next) {
        WorkOrderStatus current = status(order);
        if (!current.canTransitionTo(next)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "Work order " + order.getWorkOrderNumber() + " cannot move from " + current.code() + " to " + next.code() + ".");
        }
        order.setWorkOrderStatus(next.code());
    }

    static WorkOrderStatus status(WorkOrder order) {
        return WorkOrderStatus.fromCode(order.getWorkOrderStatus());
    }

    private static void requireSameProject(WorkOrder order, String projectId) {
        if (!order.getProjectId().equals(projectId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Referenced records must belong to the same project.");
        }
    }

    private BomRevisionView findBom(WorkOrder order, String bomId) {
        BomRevisionView bom = references.bom(bomId)
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "BOM does not exist."));
        requireSameProject(order, bom.projectId());
        return bom;
    }

    private WorkOrder findActiveOrder(String workOrderId) {
        return workOrderRepository.findByWorkOrderIdAndDeletedYn(workOrderId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private WorkOrder findOrderForUpdate(String workOrderId) {
        return workOrderRepository.findForUpdate(workOrderId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private Map<String, List<ProductionRun>> runsByWorkOrder(Collection<String> workOrderIds) {
        if (workOrderIds.isEmpty()) {
            return Map.of();
        }
        return productionRunRepository.findAllByWorkOrderIdInAndDeletedYn(workOrderIds, NOT_DELETED).stream()
            .collect(Collectors.groupingBy(ProductionRun::getWorkOrderId));
    }

    private String generateWorkOrderNumber() {
        String prefix = "WO-" + OffsetDateTime.now().format(NUMBER_DATE) + "-";
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = prefix + ThreadLocalRandom.current().nextInt(1000, 10000);
            if (!workOrderRepository.existsByWorkOrderNumber(candidate)) {
                return candidate;
            }
        }
        return prefix + idGenerator.generate().substring(0, 8);
    }

    private WorkOrderResponse toResponse(WorkOrder order) {
        return toResponse(order, runsByWorkOrder(List.of(order.getWorkOrderId())).getOrDefault(order.getWorkOrderId(), List.of()));
    }

    static WorkOrderResponse toResponse(WorkOrder order, List<ProductionRun> runs) {
        BigDecimal produced = runs.stream()
            .filter(run -> run.affectsPhysicalState() && FINISHED_RUN.equalsIgnoreCase(run.getRunStatus()))
            .map(ProductionRun::getActualOutputQty)
            .filter(qty -> qty != null)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new WorkOrderResponse(
            order.getWorkOrderId(),
            order.getProjectId(),
            order.getWorkflowId(),
            order.getWorkOrderNumber(),
            order.getWorkOrderTitle(),
            order.getWorkOrderStatus(),
            order.getPriority(),
            order.getTargetItemId(),
            order.getTargetQuantity(),
            order.getPlannedStartAt(),
            order.getPlannedEndAt(),
            order.getActualStartAt(),
            order.getActualEndAt(),
            order.getInstruction(),
            order.getAssignedTo(),
            order.getApprovedBy(),
            order.getApprovedAt(),
            produced,
            runs.size(),
            order.getBomId(),
            order.getInstructionUrl(),
            order.getEquipmentId()
        );
    }

    /**
     * Blank clears the link. Only an absolute http or https address with a host is kept, since the link is opened from
     * the work order list; anything else (javascript:, data:, a relative path) is refused.
     */
    private static String instructionUrl(String value) {
        String url = ProductionText.trimToNull(value, "instructionUrl");
        if (url == null) {
            return null;
        }
        if (url.length() > 255) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Instruction link takes at most 255 characters.");
        }
        try {
            java.net.URI uri = new java.net.URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            if ((scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null && !uri.getHost().isBlank()) {
                return url;
            }
        } catch (java.net.URISyntaxException e) {
            // Refused below.
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "Instruction link must be an http or https address, like https://docs.example.com/wi-12.pdf.");
    }

}
