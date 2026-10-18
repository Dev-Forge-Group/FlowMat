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
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.bom.application.publicapi.BomStructureQuery;
import org.myweb.flowmat.domain.catalog.application.UnitConverter;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.production.api.dto.response.RunMaterialUsageResponse;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRunItem;
import org.myweb.flowmat.domain.production.repository.ProductionRunItemRepository;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A run's material usage against its BOM (docs/domain/material-cost.md "사용량 차이"). The plan is the BOM rows the run
 * got when it started, so later BOM revisions do not change it. The actual use counts the same recordings as the run's
 * material cost ({@link RunCostService}): inputs that are not cancelled and have an actual quantity. Read only.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RunMaterialUsageService {

    private static final int SCALE = 4;
    private static final String BOM_SOURCE = "bom";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final ProductionRunRepository productionRunRepository;
    private final ProductionRunItemRepository productionRunItemRepository;
    private final CatalogQuery catalogQuery;
    private final UnitConverter unitConverter;
    private final ProjectAccessService projectAccessService;
    private final BomStructureQuery bomStructure;

    public RunMaterialUsageResponse usage(String productionRunId) {
        ProductionRun run = productionRunRepository.findByProductionRunIdAndDeletedYn(productionRunId, "N")
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        projectAccessService.requireProjectReadAccess(run.getProjectId());

        // Plan and actual per item in the item's own unit.
        Map<String, CatalogItemView> items = new LinkedHashMap<>();
        Map<String, BigDecimal> planned = new HashMap<>();
        Map<String, BigDecimal> actual = new HashMap<>();
        Set<String> unconvertible = new HashSet<>();
        List<ProductionRunItem> recordings = productionRunItemRepository.findAllByProductionRunIdOrderByProductionRunItemIdAsc(productionRunId);
        for (boolean plan : new boolean[] {true, false}) {
            for (ProductionRunItem recording : recordings) {
                boolean fromBom = BOM_SOURCE.equals(recording.getQuantitySource());
                if (!"input".equals(recording.getDirection()) || recording.isCancelled() || fromBom != plan) {
                    continue;
                }
                BigDecimal quantity = plan ? recording.getPlannedQty() : recording.getActualQty();
                if (quantity == null) {
                    continue;
                }
                CatalogItemView item = items.computeIfAbsent(recording.getItemId(), id -> catalogQuery.findItems(List.of(id)).get(id));
                if (item == null) {
                    items.remove(recording.getItemId());
                    continue;
                }
                try {
                    BigDecimal inItemUnit = unitConverter.toItemUnit(quantity, recording.getUnit(), item.unitId()).quantity();
                    (plan ? planned : actual).merge(item.itemId(), inItemUnit, BigDecimal::add);
                } catch (BusinessException e) {
                    if (!plan) {
                        unconvertible.add(item.itemId());
                    }
                }
            }
        }

        BigDecimal plannedOutput = run.getPlannedOutputQty();
        // Running records default to zero; a finished run's explicit zero is a measured output.
        boolean basisIsActual = run.getActualOutputQty() != null
            && (run.getActualOutputQty().signum() > 0
                || (run.getActualOutputQty().signum() == 0 && "finished".equalsIgnoreCase(run.getRunStatus())));
        BigDecimal basis = basisIsActual ? run.getActualOutputQty() : plannedOutput;
        boolean scalable = plannedOutput != null && plannedOutput.signum() > 0 && basis != null;

        List<RunMaterialUsageResponse.Line> lines = new ArrayList<>();
        BigDecimal varianceCost = BigDecimal.ZERO;
        boolean complete = true;
        for (CatalogItemView item : items.values()) {
            String itemId = item.itemId();
            boolean inBom = planned.containsKey(itemId);
            BigDecimal plan = inBom ? scale(planned.get(itemId)) : null;
            BigDecimal standard = !inBom ? scale(BigDecimal.ZERO)
                : scalable ? scale(planned.get(itemId).multiply(basis).divide(plannedOutput, SCALE + 4, RoundingMode.HALF_UP))
                : null;
            BigDecimal used = unconvertible.contains(itemId) ? null : scale(actual.getOrDefault(itemId, BigDecimal.ZERO));
            BigDecimal variance = used == null || standard == null ? null : used.subtract(standard);
            BigDecimal percent = variance == null || standard.signum() == 0 ? null
                : variance.multiply(HUNDRED).divide(standard, 2, RoundingMode.HALF_UP);
            BigDecimal unitCost = item.unitCost() != null && item.unitCost().signum() > 0 ? item.unitCost() : null;
            BigDecimal lineCost = variance == null || unitCost == null ? null : scale(variance.multiply(unitCost));
            if (lineCost != null) {
                varianceCost = varianceCost.add(lineCost);
            } else if (variance == null || variance.signum() != 0) {
                complete = false;
            }
            lines.add(new RunMaterialUsageResponse.Line(itemId, item.itemCode(), item.itemName(), item.unitCode(), inBom,
                plan, standard, used, variance, percent, unitCost, lineCost));
        }
        // Recordings come back in id order, which is random; list materials as the BOM lists them, then the rest by code.
        Map<String, Integer> bomOrder = new HashMap<>();
        if (run.getBomId() != null) {
            List<String> bomItems = bomStructure.lineItemIds(run.getBomId());
            for (int index = 0; index < bomItems.size(); index++) {
                bomOrder.putIfAbsent(bomItems.get(index), index);
            }
        }
        lines.sort(Comparator
            .comparing((RunMaterialUsageResponse.Line line) -> bomOrder.getOrDefault(line.itemId(), Integer.MAX_VALUE))
            .thenComparing(RunMaterialUsageResponse.Line::itemCode, Comparator.nullsLast(Comparator.naturalOrder())));
        return new RunMaterialUsageResponse(run.getProductionRunId(), run.getBomId(), run.getBomVersion(), plannedOutput, basis,
            basisIsActual, scale(varianceCost), complete, lines);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
