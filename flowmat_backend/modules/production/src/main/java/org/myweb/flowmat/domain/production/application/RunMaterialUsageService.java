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
import org.myweb.flowmat.domain.catalog.application.publicapi.UnitConversion;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.production.api.dto.response.RunMaterialUsageResponse;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRunItem;
import org.myweb.flowmat.domain.production.repository.ProductionRunItemRepository;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
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
    private final UnitConversion unitConverter;
    private final ProjectAccess projectAccessService;
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

        // A substitute the run's BOM approves stands in for its main material, so the main material's standard is shared
        // out by what each of them used, counted as the main material (ADR-032 decision 8).
        Map<String, BomStructureQuery.SubstituteShare> shares = run.getBomId() == null ? Map.of()
            : bomStructure.substituteShares(run.getBomId());
        Map<String, String> substituteFor = new HashMap<>();
        shares.forEach((substituteId, share) -> {
            if (items.containsKey(substituteId) && !planned.containsKey(substituteId) && planned.containsKey(share.mainItemId())) {
                substituteFor.put(substituteId, share.mainItemId());
            }
        });
        // An optional material its order did not pick is used beyond the plan, yet the BOM knows it (ADR-033 decision 8).
        Set<String> optional = run.getBomId() == null ? Set.of() : bomStructure.optionalItemIds(run.getBomId(), null);
        Map<String, BigDecimal> groupUse = new HashMap<>();
        Set<String> unknownGroups = new HashSet<>();
        substituteFor.forEach((substituteId, main) -> {
            if (unconvertible.contains(substituteId) || unconvertible.contains(main)) {
                unknownGroups.add(main);
            }
            groupUse.merge(main, actual.getOrDefault(substituteId, BigDecimal.ZERO).multiply(shares.get(substituteId).mainPerUnit()),
                BigDecimal::add);
        });
        groupUse.replaceAll((main, use) -> use.add(actual.getOrDefault(main, BigDecimal.ZERO)));

        List<RunMaterialUsageResponse.Line> lines = new ArrayList<>();
        BigDecimal varianceCost = BigDecimal.ZERO;
        boolean complete = true;
        for (CatalogItemView item : items.values()) {
            String itemId = item.itemId();
            String main = substituteFor.get(itemId);
            String group = main != null ? main : itemId;
            boolean inBom = planned.containsKey(itemId) || main != null;
            BigDecimal plan = planned.containsKey(itemId) ? scale(planned.get(itemId)) : null;
            BigDecimal whole = inBom && scalable
                ? planned.get(group).multiply(basis).divide(plannedOutput, SCALE + 4, RoundingMode.HALF_UP) : null;
            BigDecimal standard;
            if (!inBom) {
                standard = scale(BigDecimal.ZERO);
            } else if (whole == null || unknownGroups.contains(group)) {
                standard = null;
            } else if (!groupUse.containsKey(group) || groupUse.get(group).signum() == 0) {
                standard = scale(main == null ? whole : BigDecimal.ZERO);
            } else {
                standard = scale(whole.multiply(actual.getOrDefault(itemId, BigDecimal.ZERO))
                    .divide(groupUse.get(group), SCALE + 4, RoundingMode.HALF_UP));
            }
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
                plan, standard, used, variance, percent, unitCost, lineCost, main, !inBom && optional.contains(itemId)));
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
