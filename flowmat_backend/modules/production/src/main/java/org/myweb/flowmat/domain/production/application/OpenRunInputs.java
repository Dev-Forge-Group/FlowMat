package org.myweb.flowmat.domain.production.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.UnitConversion;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRunItem;
import org.myweb.flowmat.domain.production.repository.ProductionRunItemRepository;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.springframework.stereotype.Component;

/**
 * What work orders' unfinished physical runs (pending or running) have already recorded as input, per order and material in the
 * material's own unit. That stock is already gone, while the order's remaining quantity (target − finished output)
 * still counts it, so readiness and the open-order requirements take it off the order's need. Plan rows and cancelled
 * recordings are not inputs; a recording whose unit cannot be converted is skipped.
 */
@Component
@RequiredArgsConstructor
public class OpenRunInputs {

    private static final String NOT_DELETED = "N";
    private static final Set<String> OPEN_RUN_STATUSES = Set.of("pending", "running");

    private final ProductionRunRepository productionRunRepository;
    private final ProductionRunItemRepository productionRunItemRepository;
    private final CatalogQuery catalogQuery;
    private final UnitConversion unitConverter;

    /** For one order: material id → recorded input. */
    public Map<String, BigDecimal> forOrder(String workOrderId) {
        return byOrder(productionRunRepository.findAllByWorkOrderIdInAndDeletedYn(List.of(workOrderId), NOT_DELETED))
            .getOrDefault(workOrderId, Map.of());
    }

    /** For the orders of the given runs: order id → material id → recorded input. */
    public Map<String, Map<String, BigDecimal>> byOrder(Collection<ProductionRun> runs) {
        Map<String, String> orderOfRun = runs.stream()
            .filter(run -> run.affectsPhysicalState() && run.getWorkOrderId() != null
                && OPEN_RUN_STATUSES.contains(run.getRunStatus()))
            .collect(Collectors.toMap(ProductionRun::getProductionRunId, ProductionRun::getWorkOrderId));
        Map<String, Map<String, BigDecimal>> used = new HashMap<>();
        if (orderOfRun.isEmpty()) {
            return used;
        }
        List<ProductionRunItem> recordings = new ArrayList<>();
        for (String runId : orderOfRun.keySet()) {
            recordings.addAll(productionRunItemRepository.findAllByProductionRunIdOrderByProductionRunItemIdAsc(runId));
        }
        Map<String, CatalogItemView> items = catalogQuery.findItems(
            recordings.stream().map(ProductionRunItem::getItemId).collect(Collectors.toSet()));
        for (ProductionRunItem recording : recordings) {
            CatalogItemView item = items.get(recording.getItemId());
            BigDecimal recordedQuantity = recording.getActualQty() != null ? recording.getActualQty() : recording.getPlannedQty();
            if (!"input".equals(recording.getDirection()) || recording.isCancelled() || "bom".equals(recording.getQuantitySource())
                || recordedQuantity == null || item == null) {
                continue;
            }
            try {
                BigDecimal inItemUnit = unitConverter.toItemUnit(recordedQuantity, recording.getUnit(), item.unitId()).quantity();
                used.computeIfAbsent(orderOfRun.get(recording.getProductionRunId()), id -> new HashMap<>())
                    .merge(item.itemId(), inItemUnit, BigDecimal::add);
            } catch (BusinessException ignored) {
                // Not convertible to the material's unit: it cannot be set against the requirement.
            }
        }
        return used;
    }
}
