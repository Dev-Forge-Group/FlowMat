package org.myweb.flowmat.domain.production.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.bom.application.publicapi.BomRequirement;
import org.myweb.flowmat.domain.bom.application.publicapi.BomQuery;
import org.myweb.flowmat.domain.bom.application.publicapi.BomStructureQuery;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.production.api.dto.response.MaterialRequirementResponse;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrder;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.domain.production.repository.WorkOrderRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery;
import org.myweb.flowmat.domain.purchasing.application.publicapi.PurchaseSupplyQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.springframework.stereotype.Service;

/**
 * Material needs of all open work orders (docs/domain/material-requirements.md): for each approved or in-progress
 * order with a BOM, what is still to make (target − finished runs' output, as readiness counts it) turned into
 * materials by the BOM, less what the order's unfinished runs already recorded as input, added up per material and set
 * against the usable stock (the reorder list's rule). A material with its own approved BOM is a sub-assembly
 * (docs/domain/multi-level-bom.md): what usable stock and the open orders making it do not cover is exploded into its own
 * materials, level by level. What is ordered from suppliers and not received yet counts as supply too
 * (docs/domain/purchasing.md PO8). Like readiness it has no surrounding transaction, so an order whose BOM cannot be worked
 * out is reported, not rolled back.
 */
@Service
@RequiredArgsConstructor
public class MaterialRequirementService {

    private static final String NOT_DELETED = "N";
    private static final int SCALE = 4;

    private final WorkOrderRepository workOrderRepository;
    private final ProductionRunRepository productionRunRepository;
    private final BomQuery bomQuery;
    private final CatalogQuery catalogQuery;
    private final UsableStock usableStock;
    private final ProjectAccessService projectAccessService;
    private final ProjectCalendarQuery projectCalendar;
    private final OpenRunInputs openRunInputs;
    private final BomStructureQuery bomStructure;
    private final StockAllocationService stockAllocationService;
    private final PurchaseSupplyQuery purchaseSupply;
    private final WorkOrderOptionalItems optionalItems;

    public MaterialRequirementResponse summary(String projectId) {
        projectAccessService.requireProjectReadAccess(projectId);
        List<WorkOrder> active = workOrderRepository.findAllByProjectIdAndDeletedYnOrderByCreatedAtDesc(projectId, NOT_DELETED).stream()
            .filter(order -> WorkOrderServiceImpl.status(order).acceptsRuns())
            .toList();
        List<WorkOrder> open = active.stream()
            .filter(order -> order.getBomId() != null && order.getTargetQuantity() != null)
            .toList();
        Map<String, BigDecimal> produced = new HashMap<>();
        // Per order and material: what its unfinished runs already recorded as input. That stock is already gone, while
        // the order's remaining quantity still counts it, so it comes off the order's need.
        Map<String, Map<String, BigDecimal>> alreadyUsed = new HashMap<>();
        if (!active.isEmpty()) {
            List<ProductionRun> runs = productionRunRepository.findAllByWorkOrderIdInAndDeletedYn(
                active.stream().map(WorkOrder::getWorkOrderId).toList(), NOT_DELETED);
            for (ProductionRun run : runs) {
                if (run.affectsPhysicalState() && "finished".equalsIgnoreCase(run.getRunStatus())
                    && run.getActualOutputQty() != null) {
                    produced.merge(run.getWorkOrderId(), run.getActualOutputQty(), BigDecimal::add);
                }
            }
            alreadyUsed.putAll(openRunInputs.byOrder(runs));
        }

        Map<String, BigDecimal> required = new LinkedHashMap<>();
        Map<String, String> units = new HashMap<>();
        Map<String, List<MaterialRequirementResponse.Need>> needs = new HashMap<>();
        List<String> problems = new ArrayList<>();
        int counted = 0;
        Map<String, List<String>> picks = optionalItems.of(open.stream().map(WorkOrder::getWorkOrderId).toList());
        for (WorkOrder order : open) {
            BigDecimal remaining = order.getTargetQuantity().subtract(produced.getOrDefault(order.getWorkOrderId(), BigDecimal.ZERO));
            if (remaining.signum() <= 0) {
                continue;
            }
            BomRequirement requirement;
            try {
                // Phantom lines go through the revision of the order's planned day (multi-level-bom.md P3).
                requirement = bomQuery.requirementsForRun(order.getBomId(), projectId, order.getTargetItemId(), remaining,
                    order.getPlannedStartAt() == null ? null : projectCalendar.date(projectId, order.getPlannedStartAt().toInstant()),
                    picks.getOrDefault(order.getWorkOrderId(), List.of()));
            } catch (BusinessException exception) {
                problems.add(order.getWorkOrderTitle() + ": " + exception.getMessage());
                continue;
            }
            counted++;
            Map<String, BigDecimal> used = alreadyUsed.getOrDefault(order.getWorkOrderId(), Map.of());
            for (BomRequirement.Line line : requirement.lines()) {
                // A substitute an open run put in counts as its main material (ADR-032 decision 6).
                BigDecimal need = line.requiredItemQuantity().subtract(line.covered(used)).max(BigDecimal.ZERO);
                required.merge(line.childItemId(), need, BigDecimal::add);
                units.putIfAbsent(line.childItemId(), line.itemUnit());
                needs.computeIfAbsent(line.childItemId(), id -> new ArrayList<>())
                    .add(new MaterialRequirementResponse.Need(order.getWorkOrderId(), order.getWorkOrderTitle(), scale(need), null, null));
            }
        }

        // What open orders will still make of each item: that supply covers a sub-assembly before its BOM is exploded.
        Map<String, BigDecimal> supply = new HashMap<>();
        // The same, order by order, so the screen can tell when it comes (docs/domain/purchasing.md PO25).
        Map<String, List<MaterialRequirementResponse.Supply>> supplies = new HashMap<>();
        for (WorkOrder order : active) {
            if (order.getTargetItemId() != null && order.getTargetQuantity() != null) {
                BigDecimal remaining = order.getTargetQuantity().subtract(produced.getOrDefault(order.getWorkOrderId(), BigDecimal.ZERO));
                if (remaining.signum() > 0) {
                    supply.merge(order.getTargetItemId(), remaining, BigDecimal::add);
                    supplies.computeIfAbsent(order.getTargetItemId(), id -> new ArrayList<>())
                        .add(new MaterialRequirementResponse.Supply(order.getWorkOrderId(), order.getWorkOrderTitle(), scale(remaining)));
                }
            }
        }
        // Stock allocated to open orders is reserved for the needs counted here, so it counts as usable for them
        // (docs/domain/stock-allocation.md).
        Map<String, BigDecimal> allocated = stockAllocationService.openByItem(active.stream().map(WorkOrder::getWorkOrderId).toList());
        // Ordered from suppliers and still to come (docs/domain/purchasing.md PO8).
        Map<String, BigDecimal> onOrder = purchaseSupply.onOrder(projectId);
        // Undated, so the revisions effective on the project's today (docs/domain/multi-level-bom.md M3).
        Map<String, String> approved = bomStructure.approvedBomIdByItem(projectId, projectCalendar.today(projectId));
        explodeSubAssemblies(projectId, approved, supply, onOrder, allocated, required, units, needs, problems);

        Map<String, BigDecimal> usable = new HashMap<>(usableStock.byItem(projectId, required.keySet()));
        allocated.forEach((itemId, quantity) -> usable.merge(itemId, quantity, BigDecimal::add));
        Map<String, CatalogItemView> items = catalogQuery.findItems(required.keySet());
        List<MaterialRequirementResponse.Line> lines = required.entrySet().stream()
            .map(entry -> {
                CatalogItemView item = items.get(entry.getKey());
                BigDecimal free = usable.getOrDefault(entry.getKey(), BigDecimal.ZERO);
                BigDecimal planned = supply.getOrDefault(entry.getKey(), BigDecimal.ZERO);
                BigDecimal ordered = onOrder.getOrDefault(entry.getKey(), BigDecimal.ZERO);
                return new MaterialRequirementResponse.Line(
                    entry.getKey(),
                    item == null ? null : item.itemCode(),
                    item == null ? null : item.itemName(),
                    units.get(entry.getKey()),
                    scale(entry.getValue()),
                    scale(free),
                    scale(entry.getValue().subtract(free).subtract(planned).subtract(ordered).max(BigDecimal.ZERO)),
                    needs.getOrDefault(entry.getKey(), List.of()),
                    scale(planned),
                    approved.containsKey(entry.getKey()),
                    scale(ordered),
                    supplies.getOrDefault(entry.getKey(), List.of())
                );
            })
            .sorted(Comparator.comparing(MaterialRequirementResponse.Line::shortage).reversed()
                .thenComparing(MaterialRequirementResponse.Line::itemCode, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
        return new MaterialRequirementResponse(counted, lines, problems);
    }

    /**
     * Sub-assemblies in low-level-code order, so each one's demand from every item above it is in before its BOM is
     * exploded: what usable stock and planned supply leave short is made, and its BOM's materials are added as needs
     * "via" the sub-assembly.
     */
    private void explodeSubAssemblies(
        String projectId,
        Map<String, String> approved,
        Map<String, BigDecimal> supply,
        Map<String, BigDecimal> onOrder,
        Map<String, BigDecimal> allocated,
        Map<String, BigDecimal> required,
        Map<String, String> units,
        Map<String, List<MaterialRequirementResponse.Need>> needs,
        List<String> problems
    ) {
        if (approved.isEmpty()) {
            return;
        }
        Map<String, Integer> levels = BomStructureQuery.lowLevelCodes(bomStructure.approvedChildren(projectId));
        Set<String> done = new HashSet<>();
        while (true) {
            String next = required.keySet().stream()
                .filter(approved::containsKey)
                .filter(itemId -> !done.contains(itemId))
                .min(Comparator.comparingInt(itemId -> levels.getOrDefault(itemId, 0)))
                .orElse(null);
            if (next == null) {
                return;
            }
            done.add(next);
            BigDecimal free = usableStock.byItem(projectId, Set.of(next)).getOrDefault(next, BigDecimal.ZERO)
                .add(allocated.getOrDefault(next, BigDecimal.ZERO));
            BigDecimal toMake = required.get(next).subtract(free).subtract(supply.getOrDefault(next, BigDecimal.ZERO))
                .subtract(onOrder.getOrDefault(next, BigDecimal.ZERO));
            if (toMake.signum() <= 0) {
                continue;
            }
            String code = catalogQuery.findActiveItem(next).map(CatalogItemView::itemCode).orElse(next);
            BomRequirement below;
            try {
                below = bomQuery.requirementsForRun(approved.get(next), projectId, next, toMake);
            } catch (BusinessException exception) {
                problems.add(code + ": " + exception.getMessage());
                continue;
            }
            for (BomRequirement.Line line : below.lines()) {
                required.merge(line.childItemId(), line.requiredItemQuantity(), BigDecimal::add);
                units.putIfAbsent(line.childItemId(), line.itemUnit());
                needs.computeIfAbsent(line.childItemId(), id -> new ArrayList<>())
                    .add(new MaterialRequirementResponse.Need(null, null, scale(line.requiredItemQuantity()), next, code));
            }
        }
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
