package org.myweb.flowmat.domain.production.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.bom.application.publicapi.BomRequirement;
import org.myweb.flowmat.domain.bom.application.publicapi.BomQuery;
import org.myweb.flowmat.domain.catalog.application.UnitConverter;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockMovementCommand;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockMovementCommand.Kind;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockMovementCommand.StockMovement;
import org.myweb.flowmat.domain.inventory.application.LotService;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockView;
import org.myweb.flowmat.domain.production.api.dto.request.ProductionRunFinishRequest;
import org.myweb.flowmat.domain.production.api.dto.request.ProductionRunItemRecordRequest;
import org.myweb.flowmat.domain.production.api.dto.request.ProductionRunStartRequest;
import org.myweb.flowmat.domain.production.api.dto.response.ProductionRunItemResponse;
import org.myweb.flowmat.domain.production.api.dto.response.ProductionRunResponse;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRunItem;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrder;
import org.myweb.flowmat.domain.production.domain.enums.WorkOrderStatus;
import org.myweb.flowmat.domain.production.repository.ProductionRunItemRepository;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.domain.production.repository.WorkOrderRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery;
import org.myweb.flowmat.domain.rule.application.FlowRuleEngineService;
import org.myweb.flowmat.domain.rule.application.RuleEvaluationContext;
import org.myweb.flowmat.domain.rule.application.RuleTarget;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowAccess;
import org.myweb.flowmat.domain.workflow.application.publicapi.ProcessIoRunView;
import org.myweb.flowmat.domain.workflow.application.publicapi.ProcessRunView;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowRunQuery;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowRunView;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowProductionQuery;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemRuleFactsQuery;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockRuleFactsQuery;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockViewQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductionRunServiceImpl implements ProductionRunService {

    private static final String NOT_DELETED = "N";
    private static final Set<String> OPEN_RUN_STATUSES = Set.of("pending", "running");
    private static final Set<String> RUN_ITEM_DIRECTIONS = Set.of("input", "output");
    private static final Set<String> RUN_TYPES = Set.of("actual", "simulation", "test", "dry_run");
    private static final BigDecimal QUANTITY_OVERFLOW_BOUNDARY = new BigDecimal("9999999999.99995");
    private static final BigDecimal MIN_ROUNDABLE_QUANTITY = new BigDecimal("0.00005");

    private final ProductionRunRepository productionRunRepository;
    private final ProductionRunItemRepository productionRunItemRepository;
    private final ProjectAccessService projectAccessService;
    private final WorkflowAccess workflowAccess;
    private final WorkflowProductionQuery workflowReferences;
    private final WorkflowRunQuery workflowRuns;
    private final CatalogQuery catalogQuery;
    private final CatalogItemRuleFactsQuery ruleFacts;
    private final StockViewQuery stockViews;
    private final StockRuleFactsQuery stockRuleFactsQuery;
    private final StockMovementCommand stockMovements;
    private final FlowRuleEngineService flowRuleEngineService;
    private final IdGenerator idGenerator;
    private final WorkOrderRepository workOrderRepository;
    private final UnitConverter unitConverter;
    private final BomQuery bomQuery;
    private final LotService lotService;
    private final ProductionFlowRunAdapter productionFlowRunAdapter;
    private final StockAllocationService stockAllocationService;
    private final RunInstructionService runInstructionService;
    private final ProjectCalendarQuery projectCalendar;
    private final WorkOrderOptionalItems optionalItems;

    @Override
    public List<ProductionRunResponse> listRuns(String workflowId) {
        workflowAccess.requireRead(workflowId);
        return productionRunRepository.findAllByWorkflowIdAndDeletedYnOrderByCreatedAtDesc(workflowId, NOT_DELETED)
            .stream()
            .map(ProductionRunServiceImpl::toResponse)
            .toList();
    }

    @Override
    @Transactional
    public ProductionRunResponse startRun(ProductionRunStartRequest request) {
        ProductionText.requireStorable(request.projectId(), "projectId");
        projectAccessService.requireProjectWriteAccess(request.projectId());
        ProductionText.requireStorable(request.workflowId(), "workflowId");
        ProductionText.requireStorable(request.targetItemId(), "targetItemId");
        ProductionText.requireStorable(request.workOrderId(), "workOrderId");
        ProductionText.requireStorable(request.bomId(), "bomId");
        ProductionText.requireStorable(request.workflowRevisionId(), "workflowRevisionId");
        ProductionText.requireStorable(request.runType(), "runType");
        String runType = defaultIfBlank(request.runType(), "actual");
        if (!RUN_TYPES.contains(runType)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "runType must be actual, simulation, test or dry_run.");
        }
        request = new ProductionRunStartRequest(request.projectId(), request.workflowId(), request.targetItemId(),
            storedQuantity(request.plannedOutputQty(), "plannedOutputQty"), request.runType(), request.startedBy(),
            request.workOrderId(), request.bomId(), request.workflowRevisionId());
        WorkflowRunView workflow = findActiveWorkflow(request.workflowId());
        validateSameProject(request.projectId(), workflow.projectId());
        // Publish and retire lock the workflow; resolve its revision under the same lock.
        workflow = workflowRuns.lockWorkflow(workflow.workflowId());
        validateSameProject(request.projectId(), workflow.projectId());
        WorkflowProductionQuery.Revision workflowRevision = resolveWorkflowRevision(workflow.workflowId(), request.workflowRevisionId());
        WorkOrder workOrder = findRunnableWorkOrder(request.workOrderId(), request.projectId(), workflow);
        String targetItemId = trimToNull(request.targetItemId());
        if (targetItemId == null && workOrder != null) {
            targetItemId = workOrder.getTargetItemId();
        }
        CatalogItemView item = null;
        if (targetItemId != null) {
            item = findActiveItem(targetItemId);
            validateSameProject(request.projectId(), item.projectId());
        }
        evaluateRunStartRules(request, workflow, item);

        // Freeze the BOM now: later revisions or retirement must not change what this run planned to consume.
        String bomId = trimToNull(request.bomId());
        if (workOrder != null && workOrder.getBomId() != null) {
            if (bomId != null && !bomId.equals(workOrder.getBomId())) {
                throw new BusinessException(ErrorCode.CONFLICT, "bomId must match the revision stored on the work order.");
            }
            bomId = workOrder.getBomId();
        }
        // A phantom line uses the revision of the day the order was planned for, else today's (multi-level-bom.md P3).
        java.time.LocalDate phantomDay = workOrder != null && workOrder.getPlannedStartAt() != null
            ? projectCalendar.date(workOrder.getProjectId(), workOrder.getPlannedStartAt().toInstant())
            : null;
        BomRequirement bom = bomId == null
            ? null
            // The run freezes the optional materials its order picked; a run without an order takes none (ADR-033 decision 6).
            : bomQuery.requirementsForRun(bomId, request.projectId().trim(), targetItemId, request.plannedOutputQty(), phantomDay,
                workOrder != null ? optionalItems.of(workOrder.getWorkOrderId()) : List.of());
        if (bom != null && targetItemId == null) {
            targetItemId = bom.targetItemId();
        }

        if (workOrder != null && "actual".equals(runType)
            && WorkOrderServiceImpl.status(workOrder) == WorkOrderStatus.APPROVED) {
            // The first run against an approved order starts it.
            WorkOrderServiceImpl.transition(workOrder, WorkOrderStatus.IN_PROGRESS);
            workOrder.setActualStartAt(OffsetDateTime.now());
            workOrderRepository.save(workOrder);
        }

        ProductionRun run = new ProductionRun();
        run.setProductionRunId(idGenerator.generate());
        run.setProjectId(request.projectId().trim());
        run.setWorkflowId(workflow.workflowId());
        run.setWorkflowRevisionId(workflowRevision != null ? workflowRevision.workflowRevisionId() : null);
        run.setRunNumber(generateRunNumber());
        run.setRunType(runType);
        run.setRunStatus("running");
        run.setTargetItemId(targetItemId);
        run.setWorkOrderId(workOrder != null ? workOrder.getWorkOrderId() : null);
        run.setPlannedOutputQty(request.plannedOutputQty());
        run.setActualOutputQty(BigDecimal.ZERO);
        // Audit fields come from the authenticated user, never from the request body.
        run.setStartedBy(projectAccessService.requireCurrentUserId());
        run.setDeletedYn(NOT_DELETED);
        if (bom != null) {
            run.setBomId(bom.bomId());
            run.setBomVersion(bom.bomVersion());
            run.setBomBaseQuantity(bom.baseQuantity());
        }
        ProductionRun savedRun = productionRunRepository.save(run);
        productionFlowRunAdapter.onStarted(savedRun);

        if (bom != null) {
            for (BomRequirement.Line line : bom.lines()) {
                ProductionRunItem planned = new ProductionRunItem();
                planned.setProductionRunItemId(idGenerator.generate());
                planned.setProductionRunId(savedRun.getProductionRunId());
                planned.setItemId(line.childItemId());
                planned.setDirection("input");
                planned.setPlannedQty(line.requiredItemQuantity());
                planned.setUnit(line.itemUnit());
                planned.setConversionRate(line.conversionRate());
                planned.setViaItemId(line.viaItemId());
                planned.setQuantitySource("bom");
                productionRunItemRepository.save(planned);
            }
        }
        return toResponse(savedRun);
    }

    @Override
    public ProductionRunResponse getRun(String productionRunId) {
        ProductionRun run = findActiveRun(productionRunId);
        projectAccessService.requireProjectReadAccess(run.getProjectId());
        return toResponse(run);
    }

    @Override
    public List<ProductionRunItemResponse> listRunItems(String productionRunId) {
        ProductionRun run = findActiveRun(productionRunId);
        projectAccessService.requireProjectReadAccess(run.getProjectId());
        return productionRunItemRepository.findAllByProductionRunIdOrderByProductionRunItemIdAsc(productionRunId).stream()
            .map(ProductionRunServiceImpl::toItemResponse)
            .toList();
    }

    @Override
    @Transactional
    public ProductionRunItemResponse recordRunItem(String productionRunId, ProductionRunItemRecordRequest request) {
        ProductionRun run = findRunForUpdate(productionRunId);
        projectAccessService.requireProjectWriteAccess(run.getProjectId());
        requireOpenRun(run);
        ProductionText.requireStorable(request.itemId(), "itemId");
        ProductionText.requireStorable(request.inventoryId(), "inventoryId");
        ProductionText.requireStorable(request.processId(), "processId");
        ProductionText.requireStorable(request.processIoId(), "processIoId");
        requireKnownDirection(request.direction());
        request = new ProductionRunItemRecordRequest(request.processId(), request.processIoId(), request.inventoryId(),
            request.itemId(), request.direction(), storedQuantity(request.plannedQty(), "plannedQty"),
            request.actualQty() == null ? null : storedQuantity(request.actualQty(), "actualQty"), storedUnit(request.unit()));
        CatalogItemView item = findActiveItem(request.itemId());
        validateSameProject(run.getProjectId(), item.projectId());
        StockView inventory = null;
        RunProcessSelection selection = resolveRunProcessSelection(run, request);
        ProcessRunView process = selection.process();
        ProcessIoRunView processIo = selection.processIo();
        if (request.inventoryId() != null && !request.inventoryId().isBlank()) {
            inventory = findActiveInventory(request.inventoryId());
            validateSameProject(run.getProjectId(), inventory.projectId());
            if (!item.itemId().equals(inventory.itemId())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "The selected stock record holds a different item.");
            }
        }
        // LOT-tracked items are only consumed or produced through an explicit LOT (its stock record).
        if ("Y".equals(item.lotManageYn()) && inventory == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                item.itemCode() + " is LOT-tracked; choose the stock record (LOT) this " + request.direction().trim().toLowerCase(Locale.ROOT)
                    + " uses.");
        }

        // Reject incompatible units before anything is saved, even when no stock record is linked.
        BigDecimal recordedQty = request.actualQty() != null ? request.actualQty() : request.plannedQty();
        UnitConverter.Conversion conversion = unitConverter.toItemUnit(recordedQty, request.unit(), item.unitId());
        if (inventory != null && run.affectsPhysicalState()) {
            requireNonNegativeQuantity(conversion.quantity(), "stockQuantity");
        }

        evaluateRunItemRules(run, request, item, process, processIo, inventory);

        ProductionRunItem runItem = new ProductionRunItem();
        runItem.setProductionRunItemId(idGenerator.generate());
        runItem.setProductionRunId(run.getProductionRunId());
        runItem.setProcessId(process != null ? process.processId() : null);
        runItem.setProcessIoId(processIo != null ? processIo.processIoId() : null);
        runItem.setInventoryId(trimToNull(request.inventoryId()));
        runItem.setItemId(item.itemId());
        runItem.setDirection(request.direction().trim().toLowerCase(Locale.ROOT));
        runItem.setPlannedQty(request.plannedQty());
        runItem.setActualQty(request.actualQty());
        runItem.setUnit(request.unit().trim());
        runItem.setQuantitySource("manual");
        runItem.setLotId(inventory != null ? inventory.lotId() : null);
        ProductionRunItem savedRunItem = productionRunItemRepository.save(runItem);

        if (inventory != null && run.affectsPhysicalState()) {
            if ("input".equals(savedRunItem.getDirection()) && run.getWorkOrderId() != null && conversion.quantity() != null) {
                // Stock allocated to the run's work order is released into use first (docs/domain/stock-allocation.md).
                stockAllocationService.consume(run.getWorkOrderId(), inventory.inventoryId(),
                    conversion.quantity().setScale(4, RoundingMode.HALF_UP), savedRunItem.getProductionRunItemId());
            }
            applyInventoryEffect(run, savedRunItem, inventory, conversion);
        }
        if (savedRunItem.getLotId() != null && run.affectsPhysicalState()) {
            linkLotGenealogy(run, savedRunItem);
        }

        return toItemResponse(savedRunItem);
    }

    @Override
    @Transactional
    public ProductionRunItemResponse cancelRunItem(String productionRunId, String productionRunItemId, String reason, boolean reallocate) {
        ProductionRun run = findRunForUpdate(productionRunId);
        projectAccessService.requireProjectWriteAccess(run.getProjectId());
        requireOpenRun(run);
        ProductionRunItem item = productionRunItemRepository.findById(productionRunItemId)
            .filter(found -> found.getProductionRunId().equals(run.getProductionRunId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if ("bom".equals(item.getQuantitySource())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "BOM plan lines are the plan, not a recording; there is nothing to cancel.");
        }
        if (item.isCancelled()) {
            throw new BusinessException(ErrorCode.CONFLICT, "This item was already cancelled by " + item.getCancelledBy() + ".");
        }
        String normalizedReason = ProductionText.trimToNull(reason, "reason");
        if (normalizedReason == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Give a reason for cancelling the item.");
        }
        String actor = projectAccessService.requireCurrentUserId();
        BigDecimal returned = reallocate ? returnedToOrder(run, item) : null;

        // Stock first: if the produced stock was already used, the reversal fails and nothing else changes.
        stockMovements.reverseMovementsOf("production_run_item", item.getProductionRunItemId(),
            "Run " + run.getRunNumber() + " item cancelled: " + normalizedReason, actor);
        if (returned != null) {
            // What the input took goes back to the order rather than to free stock (docs/domain/stock-allocation.md R4).
            stockAllocationService.allocateReturned(run.getWorkOrderId(), item.getInventoryId(), returned, item.getProductionRunItemId());
        }

        item.setCancelledYn("Y");
        item.setCancelledBy(actor);
        item.setCancelledAt(OffsetDateTime.now());
        item.setCancelReason(normalizedReason);
        ProductionRunItem saved = productionRunItemRepository.save(item);

        if (saved.getLotId() != null) {
            rebuildLotGenealogy(run, saved);
        }
        return toItemResponse(saved);
    }

    /**
     * Checks, before anything changes, that a recording being cancelled can go back to its run's work order (R4): an input
     * taken from a stock record by a physical run of an order. Locks the order's allocations ahead of the stock, as recording
     * an input does, and returns how much the input took in the item's unit.
     */
    private BigDecimal returnedToOrder(ProductionRun run, ProductionRunItem item) {
        if (!"input".equals(item.getDirection()) || item.getInventoryId() == null || run.getWorkOrderId() == null
            || !run.affectsPhysicalState()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "Only an input taken from a stock record by a run of a work order can be allocated to the order again.");
        }
        BigDecimal recorded = item.getActualQty() != null ? item.getActualQty() : item.getPlannedQty();
        BigDecimal quantity = unitConverter.toItemUnit(recorded, item.getUnit(), findActiveItem(item.getItemId()).unitId()).quantity();
        if (quantity == null || quantity.setScale(4, RoundingMode.HALF_UP).signum() <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "This input took no stock; there is nothing to allocate again.");
        }
        stockAllocationService.lockForConsumption(run.getWorkOrderId());
        return quantity.setScale(4, RoundingMode.HALF_UP);
    }

    private void rebuildLotGenealogy(ProductionRun run, ProductionRunItem cancelled) {
        rebuildLotGenealogy(run, "output".equals(cancelled.getDirection()) ? List.of(cancelled.getLotId()) : List.of());
    }

    /**
     * Drops the run's genealogy and links input and output LOTs with standing stock movements again. LOTs the run used
     * to produce lose their "produced by this run" mark when no remaining physical output makes them.
     */
    @Transactional
    public void rebuildLotGenealogy(ProductionRun run, Collection<String> formerOutputLotIds) {
        lotService.clearRunTrace(run.getProductionRunId());
        List<ProductionRunItem> active = productionRunItemRepository
            .findAllByProductionRunIdOrderByProductionRunItemIdAsc(run.getProductionRunId()).stream()
            .filter(candidate -> candidate.getLotId() != null && !candidate.isCancelled())
            .toList();
        if (!run.affectsPhysicalState()) {
            // Also remove genealogy left by older non-physical recordings that wrongly touched real LOTs.
            active.stream().filter(candidate -> "output".equals(candidate.getDirection()))
                .map(ProductionRunItem::getLotId).distinct()
                .forEach(lotId -> lotService.clearProducedBy(lotId, run.getProductionRunId()));
            formerOutputLotIds.forEach(lotId -> lotService.clearProducedBy(lotId, run.getProductionRunId()));
            return;
        }
        List<ProductionRunItem> physical = productionRunItemRepository
            .findLotRecordingsWithStandingMovements(run.getProductionRunId());
        for (ProductionRunItem output : physical) {
            if ("output".equals(output.getDirection())) {
                linkLotGenealogy(run, output, physical);
            }
        }
        Set<String> outputLotIds = new HashSet<>(formerOutputLotIds);
        active.stream().filter(candidate -> "output".equals(candidate.getDirection()))
            .map(ProductionRunItem::getLotId).forEach(outputLotIds::add);
        for (String lotId : outputLotIds) {
            boolean stillProduced = physical.stream()
                .anyMatch(candidate -> "output".equals(candidate.getDirection()) && candidate.getLotId().equals(lotId));
            if (!stillProduced) {
                lotService.clearProducedBy(lotId, run.getProductionRunId());
            }
        }
    }

    // ---- Finished-run corrections (ProductionRunCorrectionServiceImpl, docs/domain/production-run-correction.md) ----

    /**
     * Checks an add_item correction line like a recording (item, stock record, LOT, unit) without saving anything. Flow
     * rules are not evaluated for corrections (§9 Q7).
     */
    public void validateCorrectionRecording(ProductionRun run, String direction, String itemId, String inventoryId, BigDecimal qty,
        String unit) {
        resolveCorrectionRecording(run, direction, itemId, inventoryId, qty, unit);
    }

    /** Records an add_item line as a new run item (quantity_source = correction) and moves its stock. */
    @Transactional
    public ProductionRunItem recordCorrectionItem(ProductionRun run, String correctionId, String direction, String itemId,
        String inventoryId, BigDecimal qty, String unit) {
        CorrectionRecording resolved = resolveCorrectionRecording(run, direction, itemId, inventoryId, qty, unit);
        ProductionRunItem runItem = new ProductionRunItem();
        runItem.setProductionRunItemId(idGenerator.generate());
        runItem.setProductionRunId(run.getProductionRunId());
        runItem.setInventoryId(resolved.inventory() != null ? resolved.inventory().inventoryId() : null);
        runItem.setItemId(resolved.item().itemId());
        runItem.setDirection(direction.trim().toLowerCase(Locale.ROOT));
        runItem.setPlannedQty(resolved.quantity());
        runItem.setActualQty(resolved.quantity());
        runItem.setUnit(storedUnit(unit));
        runItem.setQuantitySource("correction");
        runItem.setProductionRunCorrectionId(correctionId);
        runItem.setLotId(resolved.inventory() != null ? resolved.inventory().lotId() : null);
        ProductionRunItem saved = productionRunItemRepository.save(runItem);
        if (resolved.inventory() != null && run.affectsPhysicalState()) {
            applyInventoryEffect(run, saved, resolved.inventory(), resolved.conversion());
        }
        return saved;
    }

    /** Voids a recording for a correction: reverses its stock movement and marks it cancelled by that correction. */
    @Transactional
    public void voidForCorrection(ProductionRun run, ProductionRunItem item, String correctionId, int correctionNo, String reason,
        String actor) {
        stockMovements.reverseMovementsOf("production_run_item", item.getProductionRunItemId(),
            "Run " + run.getRunNumber() + " correction #" + correctionNo + ": " + reason, actor);
        item.setCancelledYn("Y");
        item.setCancelledBy(actor);
        item.setCancelledAt(OffsetDateTime.now());
        item.setCancelReason("Correction #" + correctionNo + ": " + reason);
        item.setCancelledByCorrectionId(correctionId);
        productionRunItemRepository.save(item);
    }

    private record CorrectionRecording(CatalogItemView item, StockView inventory, UnitConverter.Conversion conversion, BigDecimal quantity) {
    }

    private CorrectionRecording resolveCorrectionRecording(ProductionRun run, String direction, String itemId,
        String inventoryId, BigDecimal qty, String unit) {
        if (direction == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "direction must be 'input' or 'output'.");
        }
        requireKnownDirection(direction);
        if (qty == null || qty.signum() <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "The corrected quantity must be greater than 0.");
        }
        qty = storedQuantity(qty, "qty");
        if (qty.signum() == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "qty must round to at least 0.0001.");
        }
        unit = storedUnit(unit);
        if (itemId == null || itemId.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Choose the item to record.");
        }
        ProductionText.requireStorable(itemId, "itemId");
        ProductionText.requireStorable(inventoryId, "inventoryId");
        CatalogItemView item = findActiveItem(itemId.trim());
        validateSameProject(run.getProjectId(), item.projectId());
        StockView inventory = null;
        if (inventoryId != null && !inventoryId.isBlank()) {
            inventory = findActiveInventory(inventoryId.trim());
            validateSameProject(run.getProjectId(), inventory.projectId());
            if (!item.itemId().equals(inventory.itemId())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "The selected stock record holds a different item.");
            }
        }
        if ("Y".equals(item.lotManageYn()) && inventory == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                item.itemCode() + " is LOT-tracked; choose the stock record (LOT) this " + direction.trim().toLowerCase(Locale.ROOT)
                    + " uses.");
        }
        UnitConverter.Conversion conversion = unitConverter.toItemUnit(qty, unit.trim(), item.unitId());
        if (inventory != null && run.affectsPhysicalState()) {
            requireNonNegativeQuantity(conversion.quantity(), "stockQuantity");
        }
        return new CorrectionRecording(item, inventory, conversion, qty);
    }

    /**
     * Connects LOTs with standing stock movements: every consumed LOT is a parent of every produced LOT. Works in
     * either recording order; a zero or rounded-to-zero recording does not establish physical genealogy.
     */
    private void linkLotGenealogy(ProductionRun run, ProductionRunItem recorded) {
        linkLotGenealogy(run, recorded, productionRunItemRepository
            .findLotRecordingsWithStandingMovements(run.getProductionRunId()));
    }

    private void linkLotGenealogy(ProductionRun run, ProductionRunItem recorded, List<ProductionRunItem> physical) {
        if (physical.stream().noneMatch(candidate -> candidate.getProductionRunItemId().equals(recorded.getProductionRunItemId()))) {
            return;
        }
        boolean isOutput = "output".equals(recorded.getDirection());
        if (isOutput) {
            lotService.markProducedBy(recorded.getLotId(), run.getProductionRunId());
        }
        for (ProductionRunItem other : physical) {
            if (other.getProductionRunItemId().equals(recorded.getProductionRunItemId())
                || other.getDirection().equals(recorded.getDirection())) {
                continue;
            }
            ProductionRunItem input = isOutput ? other : recorded;
            ProductionRunItem output = isOutput ? recorded : other;
            lotService.recordTrace(
                run.getProjectId(),
                run.getProductionRunId(),
                output.getProcessId() != null ? output.getProcessId() : input.getProcessId(),
                input.getLotId(),
                output.getLotId(),
                recordedQuantity(input),
                recordedQuantity(output),
                input.getUnit()
            );
        }
    }

    private static BigDecimal recordedQuantity(ProductionRunItem item) {
        return item.getActualQty() != null ? item.getActualQty() : item.getPlannedQty();
    }

    @Override
    @Transactional
    public ProductionRunResponse finishRun(String productionRunId, ProductionRunFinishRequest request) {
        ProductionRun run = findRunForUpdate(productionRunId);
        projectAccessService.requireProjectWriteAccess(run.getProjectId());
        requireOpenRun(run);
        if (request != null && request.actualOutputQty() != null) {
            request = new ProductionRunFinishRequest(storedQuantity(request.actualOutputQty(), "actualOutputQty"),
                request.finishedBy());
        }
        // A work instruction can require its steps to be confirmed first (docs/domain/work-instruction.md).
        runInstructionService.requireCompleteToFinish(run);
        evaluateRunFinishRules(run, request);
        run.setRunStatus("finished");
        run.setActualEndAt(OffsetDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        if (request != null && request.actualOutputQty() != null) {
            run.setActualOutputQty(request.actualOutputQty());
        }
        run.setFinishedBy(projectAccessService.requireCurrentUserId());
        ProductionRun savedRun = productionRunRepository.save(run);
        productionFlowRunAdapter.onFinished(savedRun);
        return toResponse(savedRun);
    }

    private WorkflowRunView findActiveWorkflow(String workflowId) {
        return workflowRuns.findWorkflow(workflowId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private ProcessRunView findActiveProcess(String processId) {
        return workflowRuns.findProcess(processId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private ProcessIoRunView findActiveProcessIo(String processIoId) {
        return workflowRuns.findProcessIo(processIoId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private RunProcessSelection resolveRunProcessSelection(ProductionRun run, ProductionRunItemRecordRequest request) {
        String processId = trimToNull(request.processId());
        String processIoId = trimToNull(request.processIoId());
        if (processId == null && processIoId == null) {
            return new RunProcessSelection(null, null);
        }
        if (run.getWorkflowRevisionId() == null) {
            ProcessIoRunView io = processIoId != null ? findActiveProcessIo(processIoId) : null;
            if (io != null && processId != null && !processId.equals(io.processId())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Process I/O belongs to a different process.");
            }
            ProcessRunView process = findActiveProcess(processId != null ? processId : io.processId());
            validateSameWorkflow(run.getWorkflowId(), process.workflowId());
            if (io != null) {
                if (!request.itemId().equals(io.itemId())) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "itemId does not match the selected processIoId.");
                }
                if (!request.direction().trim().equalsIgnoreCase(io.direction())) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "direction does not match the selected processIoId.");
                }
            }
            return new RunProcessSelection(process, io);
        }

        WorkflowProductionQuery.Revision revision = workflowReferences
            .findRevision(run.getWorkflowRevisionId(), run.getWorkflowId())
            .orElseThrow(() -> new IllegalStateException("Run refers to a missing workflow revision."));
        // The workflow context reads its own snapshot (docs/architecture/adr/ADR-018-entity-boundary.md, 2gu).
        ProcessIoRunView io = processIoId == null ? null : workflowRuns.revisionProcessIo(revision.snapshotJson(), processIoId)
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "Process I/O is not part of this run's workflow revision."));
        if (io != null) {
            if (processId != null && !processId.equals(io.processId())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Process I/O belongs to a different process.");
            }
            if (!request.itemId().equals(io.itemId())
                || !request.direction().trim().equalsIgnoreCase(io.direction())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Process I/O item or direction differs from the published revision.");
            }
            processId = io.processId();
        }
        ProcessRunView process = workflowRuns.revisionProcess(revision.snapshotJson(), processId)
            .filter(found -> run.getWorkflowId().equals(found.workflowId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "Process is not part of this run's workflow revision."));
        return new RunProcessSelection(process, io);
    }

    private record RunProcessSelection(ProcessRunView process, ProcessIoRunView processIo) {
    }

    private CatalogItemView findActiveItem(String itemId) {
        return catalogQuery.findActiveItem(itemId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    /** The item as flow rules read it, with all its catalog properties (docs/architecture/adr/ADR-018-entity-boundary.md decision 5). */
    private Map<String, Object> itemRuleFacts(CatalogItemView item) {
        return ruleFacts.findActiveItem(item.itemId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private StockView findActiveInventory(String inventoryId) {
        return stockViews.findActiveStock(inventoryId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    /** The stock record as flow rules read it, with all its properties (docs/architecture/adr/ADR-018-entity-boundary.md decision 5). */
    private Map<String, Object> stockRuleFacts(StockView inventory) {
        return stockRuleFactsQuery.findActiveStock(inventory.inventoryId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private WorkOrder findRunnableWorkOrder(String workOrderId, String projectId, WorkflowRunView workflow) {
        String normalized = trimToNull(workOrderId);
        if (normalized == null) {
            return null;
        }
        WorkOrder workOrder = workOrderRepository.findForUpdate(normalized)
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "Work order does not exist."));
        validateSameProject(projectId.trim(), workOrder.getProjectId());
        if (workOrder.getWorkflowId() != null && !workOrder.getWorkflowId().equals(workflow.workflowId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "Work order " + workOrder.getWorkOrderNumber() + " belongs to a different workflow.");
        }
        if (!WorkOrderServiceImpl.status(workOrder).acceptsRuns()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "Work order " + workOrder.getWorkOrderNumber() + " is " + workOrder.getWorkOrderStatus()
                    + "; runs can only start on approved or in-progress orders.");
        }
        return workOrder;
    }

    private ProductionRun findActiveRun(String productionRunId) {
        return productionRunRepository.findByProductionRunIdAndDeletedYn(productionRunId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private ProductionRun findRunForUpdate(String productionRunId) {
        return productionRunRepository.findForUpdate(productionRunId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private static ProductionRunResponse toResponse(ProductionRun run) {
        return new ProductionRunResponse(
            run.getProductionRunId(),
            run.getProjectId(),
            run.getWorkflowId(),
            run.getWorkflowRevisionId(),
            run.getRunNumber(),
            run.getRunType(),
            run.getRunStatus(),
            run.getTargetItemId(),
            run.getPlannedOutputQty(),
            run.getActualOutputQty(),
            run.getWorkOrderId(),
            run.getBomId(),
            run.getBomVersion(),
            run.getActualEndAt()
        );
    }

    private WorkflowProductionQuery.Revision resolveWorkflowRevision(String workflowId, String requestedRevisionId) {
        if (requestedRevisionId == null || requestedRevisionId.isBlank()) {
            return workflowReferences.latestPublishedRevision(workflowId)
                .orElse(null);
        }
        WorkflowProductionQuery.Revision revision = workflowReferences
            .findRevision(requestedRevisionId.trim(), workflowId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "Workflow revision was not found for this workflow."));
        if (!"published".equals(revision.status())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "Retired workflow revisions cannot start new runs.");
        }
        return revision;
    }

    private static ProductionRunItemResponse toItemResponse(ProductionRunItem item) {
        return new ProductionRunItemResponse(
            item.getProductionRunItemId(),
            item.getProductionRunId(),
            item.getProcessId(),
            item.getProcessIoId(),
            item.getInventoryId(),
            item.getItemId(),
            item.getDirection(),
            item.getPlannedQty(),
            item.getActualQty(),
            item.getUnit(),
            item.getQuantitySource(),
            item.getConversionRate(),
            item.getLotId(),
            item.isCancelled(),
            item.getCancelledBy(),
            item.getCancelledAt(),
            item.getCancelReason(),
            item.getProductionRunCorrectionId(),
            item.getCancelledByCorrectionId(),
            item.getViaItemId()
        );
    }

    static void requireOpenRun(ProductionRun run) {
        String status = run.getRunStatus() == null ? "" : run.getRunStatus().trim().toLowerCase(Locale.ROOT);
        if (!OPEN_RUN_STATUSES.contains(status)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Run " + run.getRunNumber() + " is already " + status + ".");
        }
    }

    private static void requireKnownDirection(String direction) {
        ProductionText.requireStorable(direction, "direction");
        if (!RUN_ITEM_DIRECTIONS.contains(direction.trim().toLowerCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "direction must be 'input' or 'output'.");
        }
    }

    static void requireNonNegativeQuantity(BigDecimal quantity, String field) {
        if (quantity == null || quantity.signum() < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, field + " must be 0 or more.");
        }
        // numeric(14,4) rounds first: even a value below 10^10 can overflow at the half-up boundary.
        // Compare without rescaling so excessive exponents cannot expand during validation.
        if (quantity.compareTo(QUANTITY_OVERFLOW_BOUNDARY) >= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                field + " must round to at most 9999999999.9999.");
        }
    }

    static String storedUnit(String unit) {
        String normalized = ProductionText.trimToNull(unit, "unit");
        if (normalized == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "unit is required.");
        }
        if (normalized.codePointCount(0, normalized.length()) > 20) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "unit must be 20 characters or fewer.");
        }
        return normalized;
    }

    static BigDecimal storedQuantity(BigDecimal quantity, String field) {
        requireNonNegativeQuantity(quantity, field);
        if (quantity.scale() <= 4) {
            return quantity;
        }
        if (quantity.compareTo(MIN_ROUNDABLE_QUANTITY) < 0) {
            return BigDecimal.ZERO;
        }
        return quantity.setScale(4, RoundingMode.HALF_UP);
    }

    private static void validateSameProject(String expectedProjectId, String actualProjectId) {
        if (!expectedProjectId.equals(actualProjectId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
    }

    private static void validateSameWorkflow(String expectedWorkflowId, String actualWorkflowId) {
        if (!expectedWorkflowId.equals(actualWorkflowId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
    }

    private static String trimToNull(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        return value != null && !value.isBlank() ? value.trim().toLowerCase(Locale.ROOT) : defaultValue;
    }

    private void applyInventoryEffect(
        ProductionRun run,
        ProductionRunItem runItem,
        StockView inventory,
        UnitConverter.Conversion conversion
    ) {
        // Stock is kept in the item's unit; inventory columns hold 4 decimals.
        BigDecimal actualQty = conversion.quantity() == null
            ? BigDecimal.ZERO
            : conversion.quantity().setScale(4, RoundingMode.HALF_UP);

        Kind type;
        BigDecimal quantityDelta;
        if ("input".equals(runItem.getDirection())) {
            type = Kind.PRODUCTION_INPUT;
            quantityDelta = actualQty.negate();
        } else if ("output".equals(runItem.getDirection())) {
            type = Kind.PRODUCTION_OUTPUT;
            quantityDelta = actualQty;
        } else {
            return;
        }
        if (quantityDelta.signum() == 0) {
            return;
        }

        // Conditional atomic UPDATE: concurrent runs cannot lose updates, and consuming more than is available
        // (or quarantined stock) is refused instead of driving the record negative.
        stockMovements.apply(new StockMovement(
            inventory.inventoryId(),
            type,
            quantityDelta,
            BigDecimal.ZERO,
            "production_run_item",
            runItem.getProductionRunItemId(),
            conversion.converted()
                ? "Recorded from run " + run.getRunNumber() + " ("
                    + (runItem.getActualQty() != null ? runItem.getActualQty() : runItem.getPlannedQty()).stripTrailingZeros().toPlainString()
                    + " " + conversion.fromUnitCode() + " = " + actualQty.stripTrailingZeros().toPlainString()
                    + " " + conversion.toUnitCode() + ")"
                : "Recorded from run " + run.getRunNumber(),
            null,
            projectAccessService.requireCurrentUserId()
        ));
    }

    private static BigDecimal defaultIfNull(BigDecimal value, BigDecimal defaultValue) {
        return value != null ? value : defaultValue;
    }

    private static String generateRunNumber() {
        long timestamp = System.currentTimeMillis();
        int suffix = ThreadLocalRandom.current().nextInt(1000, 9999);
        return "RUN-" + timestamp + "-" + suffix;
    }

    private void evaluateRunStartRules(ProductionRunStartRequest request, WorkflowRunView workflow, CatalogItemView item) {
        List<RuleTarget> targets = new ArrayList<>();
        targets.add(new RuleTarget("project", request.projectId()));
        targets.add(new RuleTarget("workflow", workflow.workflowId()));
        if (item != null) {
            targets.add(new RuleTarget("item", item.itemId()));
        }

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("operation", "run_start");
        facts.put("request", request);
        facts.put("workflow", workflow.ruleFacts());
        facts.put("item", item == null ? null : itemRuleFacts(item));
        facts.put("plannedOutputQty", request.plannedOutputQty());
        facts.put("runType", defaultIfBlank(request.runType(), "actual"));

        flowRuleEngineService.validateRules(new RuleEvaluationContext(request.projectId().trim(), targets, facts));
    }

    private void evaluateRunItemRules(
        ProductionRun run,
        ProductionRunItemRecordRequest request,
        CatalogItemView item,
        ProcessRunView process,
        ProcessIoRunView processIo,
        StockView inventory
    ) {
        List<RuleTarget> targets = new ArrayList<>();
        targets.add(new RuleTarget("project", run.getProjectId()));
        targets.add(new RuleTarget("workflow", run.getWorkflowId()));
        targets.add(new RuleTarget("run", run.getProductionRunId()));
        targets.add(new RuleTarget("item", item.itemId()));
        if (process != null) {
            targets.add(new RuleTarget("process", process.processId()));
        }
        if (processIo != null) {
            targets.add(new RuleTarget("process_io", processIo.processIoId()));
        }
        if (inventory != null) {
            targets.add(new RuleTarget("inventory", inventory.inventoryId()));
        }

        BigDecimal requestQuantity = request.actualQty() != null ? request.actualQty() : request.plannedQty();
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("operation", "run_item_record");
        facts.put("run", run);
        facts.put("request", request);
        facts.put("item", itemRuleFacts(item));
        facts.put("process", process == null ? null : process.ruleFacts());
        facts.put("processIo", processIo == null ? null : processIo.ruleFacts());
        facts.put("inventory", inventory == null ? null : stockRuleFacts(inventory));
        facts.put("requestQuantity", requestQuantity);
        facts.put("direction", request.direction().trim().toLowerCase(Locale.ROOT));

        flowRuleEngineService.validateRules(new RuleEvaluationContext(run.getProjectId(), targets, facts));
    }

    private void evaluateRunFinishRules(ProductionRun run, ProductionRunFinishRequest request) {
        List<RuleTarget> targets = List.of(
            new RuleTarget("project", run.getProjectId()),
            new RuleTarget("workflow", run.getWorkflowId()),
            new RuleTarget("run", run.getProductionRunId())
        );

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("operation", "run_finish");
        facts.put("run", run);
        facts.put("request", request);
        if (request != null) {
            facts.put("actualOutputQty", request.actualOutputQty());
            facts.put("finishedBy", request.finishedBy());
        }

        flowRuleEngineService.validateRules(new RuleEvaluationContext(run.getProjectId(), new ArrayList<>(targets), facts));
    }
}
