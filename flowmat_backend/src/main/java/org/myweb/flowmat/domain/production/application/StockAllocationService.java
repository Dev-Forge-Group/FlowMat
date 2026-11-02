package org.myweb.flowmat.domain.production.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.bom.api.dto.response.BomRequirementResponse;
import org.myweb.flowmat.domain.bom.application.BomService;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.inventory.application.InventoryCommandService;
import org.myweb.flowmat.domain.inventory.application.InventoryMovement;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockView;
import org.myweb.flowmat.domain.inventory.application.publicapi.LotView;
import org.myweb.flowmat.domain.inventory.domain.enums.InventoryTransactionType;
import org.myweb.flowmat.domain.inventory.domain.enums.LotStatus;
import org.myweb.flowmat.domain.production.api.dto.request.StockAllocationRequest;
import org.myweb.flowmat.domain.production.api.dto.response.StockAllocationResponse;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.myweb.flowmat.domain.production.domain.entity.StockAllocation;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrder;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.domain.production.repository.StockAllocationRepository;
import org.myweb.flowmat.domain.production.repository.WorkOrderRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock allocated to work orders (docs/domain/stock-allocation.md). Allocating reserves usable stock for the order,
 * expiring LOTs first; a run of the order that takes from an allocated record releases that much of the reservation into
 * use in the same transaction; what is left can be released. Reservations go through the ordinary stock commands.
 */
@Service
@RequiredArgsConstructor
public class StockAllocationService {

    static final String OPEN = "open";
    static final String CLOSED = "closed";
    private static final String NOT_DELETED = "N";
    private static final String AVAILABLE = "available";
    private static final String REFERENCE = "stock_allocation";
    private static final BigDecimal MIN_ROUNDABLE_QUANTITY = new BigDecimal("0.00005");

    private final StockAllocationRepository allocationRepository;
    private final ProductionPlanningReferences references;
    private final org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery projectCalendar;
    private final WorkOrderRepository workOrderRepository;
    private final ProductionRunRepository productionRunRepository;
    private final BomService bomService;
    private final OpenRunInputs openRunInputs;
    private final InventoryCommandService inventoryCommandService;
    private final ProjectAccessService projectAccessService;
    private final IdGenerator idGenerator;
    private final WorkOrderOptionalItems optionalItems;

    @Transactional(readOnly = true)
    public StockAllocationResponse list(String workOrderId) {
        WorkOrder order = findOrder(workOrderId);
        projectAccessService.requireProjectReadAccess(order.getProjectId());
        return response(order, List.of());
    }

    @Transactional
    public StockAllocationResponse allocate(String workOrderId, StockAllocationRequest request) {
        WorkOrder order = lockOrder(workOrderId);
        String projectId = order.getProjectId();
        projectAccessService.requireProjectWriteAccess(projectId);
        if (!WorkOrderServiceImpl.status(order).acceptsRuns()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Work order " + order.getWorkOrderNumber() + " is "
                + order.getWorkOrderStatus() + "; allocate for an approved or started order.");
        }
        lockAllocations(order);
        Map<String, List<BomRequirementResponse.Substitute>> substitutes = new HashMap<>();
        Map<String, BigDecimal> needs = needs(order, request, substitutes);
        String actor = projectAccessService.requireCurrentUserId();
        Map<String, BigDecimal> before = new HashMap<>();
        allocationRepository.findAllByWorkOrderIdAndStatusOrderByCreatedAtAscAllocationIdAsc(order.getWorkOrderId(), OPEN)
            .forEach(allocation -> before.merge(allocation.getItemId(), allocation.remaining(), BigDecimal::add));
        LocalDate today = projectCalendar.today(projectId);
        List<StockAllocationResponse.PlanLine> plan = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> need : needs.entrySet()) {
            CatalogItemView item = references.item(need.getKey())
                .filter(found -> projectId.equals(found.projectId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "An item to allocate was not found in this project."));
            BigDecimal already = before.getOrDefault(item.itemId(), BigDecimal.ZERO);
            // A substitute already allocated to the order counts as this material (ADR-032 decision 6).
            for (BomRequirementResponse.Substitute substitute : substitutes.getOrDefault(item.itemId(), List.of())) {
                BigDecimal held = before.get(substitute.childItemId());
                if (held != null && substitute.mainPerUnit() != null) {
                    already = already.add(scale(held.multiply(substitute.mainPerUnit())));
                }
            }
            BigDecimal left = need.getValue().subtract(already);
            BigDecimal now = BigDecimal.ZERO;
            if (left.signum() > 0) {
                // Locked in id order, so a movement that would drain a record this plan counts on waits for it.
                List<StockView> rows = references.lockItemStock(projectId, item.itemId());
                for (StockView row : candidates(rows, today)) {
                    if (left.signum() <= 0) {
                        break;
                    }
                    BigDecimal take = row.availableQuantity().min(left);
                    open(order, row, take, actor, "Allocated to work order " + order.getWorkOrderNumber());
                    now = now.add(take);
                    left = left.subtract(take);
                }
            }
            plan.add(new StockAllocationResponse.PlanLine(item.itemId(), item.itemCode(), scale(need.getValue()), scale(already),
                scale(now), scale(left.max(BigDecimal.ZERO))));
        }
        return response(order, plan);
    }

    @Transactional
    public StockAllocationResponse release(String workOrderId, String allocationId) {
        WorkOrder order = lockOrder(workOrderId);
        projectAccessService.requireProjectWriteAccess(order.getProjectId());
        lockAllocations(order);
        StockAllocation allocation = allocationRepository.findById(allocationId)
            .filter(found -> found.getWorkOrderId().equals(order.getWorkOrderId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!OPEN.equals(allocation.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "The allocation is already closed.");
        }
        releaseRest(order, allocation, projectAccessService.requireCurrentUserId());
        return response(order, List.of());
    }

    /** Gives back everything still allocated to the order; also for completed or cancelled orders. */
    @Transactional
    public StockAllocationResponse releaseAll(String workOrderId) {
        WorkOrder order = lockOrder(workOrderId);
        projectAccessService.requireProjectWriteAccess(order.getProjectId());
        lockAllocations(order);
        String actor = projectAccessService.requireCurrentUserId();
        for (StockAllocation allocation : allocationRepository.findAllByWorkOrderIdAndStatusOrderByCreatedAtAscAllocationIdAsc(
            order.getWorkOrderId(), OPEN)) {
            releaseRest(order, allocation, actor);
        }
        return response(order, List.of());
    }

    /**
     * A run of the order takes {@code quantity} (in the item's unit) from a stock record: that much of the order's open
     * allocations on the record, oldest first, is released into use first, so the run's input finds it available. Runs
     * inside the caller's transaction; the caller has checked access. Returns what was released.
     */
    @Transactional
    public BigDecimal consume(String workOrderId, String inventoryId, BigDecimal quantity, String runItemId) {
        lockForConsumption(workOrderId);
        BigDecimal left = quantity;
        BigDecimal released = BigDecimal.ZERO;
        String actor = projectAccessService.requireCurrentUserId();
        for (StockAllocation allocation : allocationRepository.findAllByWorkOrderIdAndInventoryIdAndStatusOrderByCreatedAtAscAllocationIdAsc(
            workOrderId, inventoryId, OPEN)) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal take = allocation.remaining().min(left);
            if (take.signum() <= 0) {
                continue;
            }
            inventoryCommandService.apply(new InventoryMovement(inventoryId, InventoryTransactionType.RELEASE, BigDecimal.ZERO, take.negate(),
                REFERENCE, allocation.getAllocationId(), "Used by production run item " + runItemId, null, actor));
            allocation.setConsumedQuantity(allocation.getConsumedQuantity().add(take));
            closeIfSettled(allocation);
            allocationRepository.save(allocation);
            left = left.subtract(take);
            released = released.add(take);
        }
        return released;
    }

    /**
     * A cancelled input of the order's run gave {@code quantity} (in the item's unit) back to a stock record; all of it is
     * allocated to the order again as a new allocation (docs/domain/stock-allocation.md R4), or nothing is. Runs inside the
     * caller's transaction after {@link #lockForConsumption}; the caller has checked access.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal allocateReturned(String workOrderId, String inventoryId, BigDecimal quantity, String runItemId) {
        WorkOrder order = lockOrder(workOrderId);
        if (!WorkOrderServiceImpl.status(order).acceptsRuns()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Work order " + order.getWorkOrderNumber() + " is "
                + order.getWorkOrderStatus() + "; the returned stock cannot be allocated to it again.");
        }
        // Read after the reversal, which holds the record's lock until commit.
        StockView row = references.stocks(List.of(inventoryId)).stream().findFirst()
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        List<StockView> usable = candidates(List.of(row), projectCalendar.today(order.getProjectId()));
        if (usable.isEmpty() || usable.get(0).availableQuantity().compareTo(quantity) < 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "The returned stock cannot be allocated again: the record is not available, "
                + "its LOT is not usable or has expired, or it no longer has that much free stock. Cancel without allocating it again.");
        }
        open(order, row, quantity, projectAccessService.requireCurrentUserId(),
            "Allocated to work order " + order.getWorkOrderNumber() + " again: run item " + runItemId + " was cancelled");
        return quantity;
    }

    /**
     * The order is completed or cancelled, so nothing more is made for it: what is still allocated goes back to free stock.
     * Runs inside the caller's transaction; the caller has checked access. Returns how many allocations were closed.
     */
    @Transactional
    public int releaseOpenOf(WorkOrder order) {
        lockForConsumption(order.getWorkOrderId());
        List<StockAllocation> open = allocationRepository.findAllByWorkOrderIdAndStatusOrderByCreatedAtAscAllocationIdAsc(
            order.getWorkOrderId(), OPEN);
        if (open.isEmpty()) {
            return 0;
        }
        String actor = projectAccessService.requireCurrentUserId();
        open.forEach(allocation -> releaseRest(order, allocation, actor));
        return open.size();
    }

    /**
     * Called after locking a run and checking access, before reading its reservations or locking any stock.
     * Holds the order and project allocation locks in the caller's transaction, including all LOT split recordings.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForConsumption(String workOrderId) {
        lockAllocations(lockOrder(workOrderId));
    }

    /** Still allocated, per item, to the given work orders. For material needs. */
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> openByItem(Collection<String> workOrderIds) {
        Map<String, BigDecimal> byItem = new HashMap<>();
        if (!workOrderIds.isEmpty()) {
            allocationRepository.findAllByWorkOrderIdInAndStatus(workOrderIds, OPEN)
                .forEach(allocation -> byItem.merge(allocation.getItemId(), allocation.remaining(), BigDecimal::add));
        }
        return byItem;
    }

    /** A new open allocation of {@code quantity} on the record for the order, reserved through the stock commands. */
    private void open(WorkOrder order, StockView row, BigDecimal quantity, String actor, String note) {
        StockAllocation allocation = new StockAllocation();
        allocation.setAllocationId(idGenerator.generate());
        allocation.setProjectId(order.getProjectId());
        allocation.setWorkOrderId(order.getWorkOrderId());
        allocation.setInventoryId(row.inventoryId());
        allocation.setItemId(row.itemId());
        allocation.setLotId(row.lotId());
        allocation.setQuantity(quantity);
        allocation.setConsumedQuantity(BigDecimal.ZERO);
        allocation.setReleasedQuantity(BigDecimal.ZERO);
        allocation.setStatus(OPEN);
        allocation.setCreatedBy(actor);
        allocation.setCreatedAt(OffsetDateTime.now());
        allocationRepository.saveAndFlush(allocation);
        inventoryCommandService.apply(new InventoryMovement(row.inventoryId(), InventoryTransactionType.RESERVE,
            BigDecimal.ZERO, quantity, REFERENCE, allocation.getAllocationId(), note, null, actor));
    }

    private void releaseRest(WorkOrder order, StockAllocation allocation, String actor) {
        BigDecimal rest = allocation.remaining();
        if (rest.signum() > 0) {
            inventoryCommandService.apply(new InventoryMovement(allocation.getInventoryId(), InventoryTransactionType.RELEASE,
                BigDecimal.ZERO, rest.negate(), REFERENCE, allocation.getAllocationId(),
                "Released from work order " + order.getWorkOrderNumber(), null, actor));
            allocation.setReleasedQuantity(allocation.getReleasedQuantity().add(rest));
        }
        closeIfSettled(allocation);
        allocationRepository.save(allocation);
    }

    private static void closeIfSettled(StockAllocation allocation) {
        if (allocation.remaining().signum() <= 0) {
            allocation.setStatus(CLOSED);
            allocation.setClosedAt(OffsetDateTime.now());
        }
    }

    /**
     * Explicit lines, or the order's BOM for what is still to make less what its unfinished runs already took, a substitute
     * as its main material (ADR-032 decision 6). {@code substitutes} gets each BOM material's substitutes.
     */
    private Map<String, BigDecimal> needs(WorkOrder order, StockAllocationRequest request,
                                          Map<String, List<BomRequirementResponse.Substitute>> substitutes) {
        Map<String, BigDecimal> needs = new LinkedHashMap<>();
        List<StockAllocationRequest.Line> lines = request == null || request.lines() == null ? List.of() : request.lines();
        if (!lines.isEmpty()) {
            for (StockAllocationRequest.Line line : lines) {
                if (line == null) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "lines must not contain null entries.");
                }
                String itemId = ProductionText.trimToNull(line.itemId(), "lines.itemId");
                if (itemId == null || line.quantity() == null || line.quantity().signum() <= 0) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "Each line needs an item and a quantity greater than 0.");
                }
                needs.merge(itemId, line.quantity(), BigDecimal::add);
            }
            // Sum repeated item lines first, then use the same precision as allocations and stock movements.
            // Reject amounts that cannot reserve any stock instead of relying on a database constraint failure.
            needs.replaceAll((itemId, quantity) -> {
                BigDecimal stored = scale(quantity);
                if (stored.signum() <= 0) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "lines.quantity must round to at least 0.0001 in the item's stock unit.");
                }
                return stored;
            });
            return needs;
        }
        if (order.getBomId() == null || order.getTargetQuantity() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "Work order " + order.getWorkOrderNumber() + " has no BOM and quantity; give the lines to allocate.");
        }
        BigDecimal produced = productionRunRepository.findAllByWorkOrderIdInAndDeletedYn(List.of(order.getWorkOrderId()), NOT_DELETED)
            .stream()
            .filter(run -> run.affectsPhysicalState() && "finished".equalsIgnoreCase(run.getRunStatus()))
            .map(ProductionRun::getActualOutputQty)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal remaining = order.getTargetQuantity().subtract(produced);
        if (remaining.signum() <= 0) {
            return needs;
        }
        // Phantoms expand as the order's run will freeze them: the planned day's revision (docs/domain/multi-level-bom.md P3).
        LocalDate phantomDay = order.getPlannedStartAt() == null ? null
            : projectCalendar.date(order.getProjectId(), order.getPlannedStartAt().toInstant());
        BomRequirementResponse requirement = bomService.requirementsForRun(order.getBomId(), order.getProjectId(), order.getTargetItemId(),
            remaining, phantomDay, optionalItems.of(order.getWorkOrderId()));
        Map<String, BigDecimal> used = openRunInputs.forOrder(order.getWorkOrderId());
        for (BomRequirementResponse.Line line : requirement.lines()) {
            BigDecimal need = scale(line.requiredItemQuantity().subtract(line.covered(used)));
            if (need.signum() > 0) {
                needs.merge(line.childItemId(), need, BigDecimal::add);
            }
            if (!line.substitutes().isEmpty()) {
                substitutes.computeIfAbsent(line.childItemId(), id -> new ArrayList<>()).addAll(line.substitutes());
            }
        }
        return needs;
    }

    /** Usable records with free stock: available status, usable LOT not expired; expiring LOTs first, then oldest. */
    private List<StockView> candidates(List<StockView> rows, LocalDate today) {
        Map<String, LotView> lots = references.lots(rows.stream().map(StockView::lotId).filter(Objects::nonNull).distinct().toList())
            .stream().collect(Collectors.toMap(LotView::lotId, Function.identity()));
        return rows.stream()
            .filter(row -> AVAILABLE.equals(row.inventoryStatus()))
            .filter(row -> row.availableQuantity() != null && row.availableQuantity().signum() > 0)
            .filter(row -> {
                LotView lot = row.lotId() == null ? null : lots.get(row.lotId());
                return row.lotId() == null || (lot != null && LotStatus.fromCode(lot.lotStatus()).usable() && !lot.isExpiredOn(today));
            })
            .sorted(Comparator
                .comparing((StockView row) -> row.lotId() == null ? null : lots.get(row.lotId()).expiryDate(),
                    Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(StockView::createdAt, Comparator.nullsLast(Comparator.<OffsetDateTime>naturalOrder()))
                .thenComparing(StockView::inventoryId))
            .toList();
    }

    private StockAllocationResponse response(WorkOrder order, List<StockAllocationResponse.PlanLine> plan) {
        List<StockAllocation> allocations = allocationRepository.findAllByWorkOrderIdOrderByCreatedAtAscAllocationIdAsc(order.getWorkOrderId());
        Map<String, CatalogItemView> items = references.items(allocations.stream().map(StockAllocation::getItemId).distinct().toList())
            .stream().collect(Collectors.toMap(CatalogItemView::itemId, Function.identity()));
        Map<String, LotView> lots = references.lots(allocations.stream().map(StockAllocation::getLotId)
                .filter(Objects::nonNull).distinct().toList())
            .stream().collect(Collectors.toMap(LotView::lotId, Function.identity()));
        Map<String, StockView> rows = references.stocks(allocations.stream().map(StockAllocation::getInventoryId).distinct().toList())
            .stream().collect(Collectors.toMap(StockView::inventoryId, Function.identity()));
        List<StockAllocationResponse.Allocation> list = allocations.stream()
            .map(allocation -> {
                CatalogItemView item = items.get(allocation.getItemId());
                LotView lot = allocation.getLotId() == null ? null : lots.get(allocation.getLotId());
                StockView row = rows.get(allocation.getInventoryId());
                return new StockAllocationResponse.Allocation(allocation.getAllocationId(), allocation.getInventoryId(), allocation.getItemId(),
                    item == null ? null : item.itemCode(), allocation.getLotId(), lot == null ? null : lot.lotNo(),
                    row == null ? null : row.location(), scale(allocation.getQuantity()), scale(allocation.getConsumedQuantity()),
                    scale(allocation.getReleasedQuantity()), scale(allocation.remaining()), allocation.getStatus(),
                    allocation.getCreatedBy(), allocation.getCreatedAt());
            })
            .toList();
        return new StockAllocationResponse(order.getWorkOrderId(), order.getWorkOrderNumber(), order.getWorkOrderStatus(), list, plan);
    }

    private WorkOrder findOrder(String workOrderId) {
        return workOrderRepository.findByWorkOrderIdAndDeletedYn(workOrderId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private WorkOrder lockOrder(String workOrderId) {
        return workOrderRepository.findForUpdate(workOrderId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private void lockAllocations(WorkOrder order) {
        allocationRepository.lockKey("stock-allocation|" + order.getProjectId());
    }

    private static BigDecimal scale(BigDecimal value) {
        if (value.abs().compareTo(MIN_ROUNDABLE_QUANTITY) < 0) {
            return BigDecimal.ZERO;
        }
        return value.setScale(4, RoundingMode.HALF_UP);
    }
}
