package org.myweb.flowmat.domain.production.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.bom.application.publicapi.BomOutputQuery;
import org.myweb.flowmat.domain.catalog.application.UnitConverter;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogDisposalCostQuery;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogUnitCostView.CostBasis;
import org.myweb.flowmat.domain.production.api.dto.response.RunWasteDisposalResponse;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRunItem;
import org.myweb.flowmat.domain.production.repository.ProductionRunItemRepository;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prices the run's recorded, uncancelled waste outputs of the pinned BOM with the items' disposal costs, on the same D+
 * basis as material cost and by-product value: current while running, at the original finish once finished
 * (docs/domain/bom-by-products.md WD5).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RunWasteDisposalService {
    private static final int SCALE = 4;
    private final ProductionRunRepository runs;
    private final ProductionRunItemRepository recordings;
    private final BomOutputQuery bomOutputs;
    private final CatalogQuery catalog;
    private final CatalogDisposalCostQuery disposalCosts;
    private final UnitConverter units;
    private final ProjectAccessService access;

    public RunWasteDisposalResponse cost(String runId) {
        var run = runs.findByProductionRunIdAndDeletedYn(runId, "N")
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        access.requireProjectReadAccess(run.getProjectId());
        boolean finished = "finished".equalsIgnoreCase(run.getRunStatus());
        OffsetDateTime at = finished ? run.getActualEndAt() : null;
        CostBasis basis = !finished ? CostBasis.CURRENT : at == null ? CostBasis.ESTIMATED : CostBasis.HISTORICAL;
        var waste = bomOutputs.findWasteItemIds(run.getProjectId(), run.getBomId());
        var rows = recordings.findAllByProductionRunIdOrderByProductionRunItemIdAsc(runId).stream()
            .filter(row -> "output".equals(row.getDirection()) && !row.isCancelled() && row.getActualQty() != null
                && waste.contains(row.getItemId())).toList();
        var ids = rows.stream().map(ProductionRunItem::getItemId).distinct().toList();
        var items = catalog.findItems(ids);
        var prices = disposalCosts.findDisposalCostsAt(run.getProjectId(), ids, at);
        Map<String, BigDecimal> quantities = new LinkedHashMap<>();
        Map<String, Boolean> convertible = new LinkedHashMap<>();
        for (var row : rows) {
            String id = row.getItemId();
            var item = items.get(id);
            convertible.putIfAbsent(id, true);
            if (item == null || !run.getProjectId().equals(item.projectId())) {
                convertible.put(id, false);
                continue;
            }
            try {
                quantities.merge(id, units.toItemUnit(row.getActualQty(), row.getUnit(), item.unitId()).quantity(), BigDecimal::add);
            } catch (BusinessException error) {
                convertible.put(id, false);
            }
        }
        var lines = new ArrayList<RunWasteDisposalResponse.Line>();
        BigDecimal total = BigDecimal.ZERO;
        boolean complete = true;
        for (var entry : convertible.entrySet()) {
            String id = entry.getKey();
            var item = items.get(id);
            if (item != null && !run.getProjectId().equals(item.projectId())) item = null;
            var price = prices.get(id);
            BigDecimal quantity = quantities.getOrDefault(id, BigDecimal.ZERO).setScale(SCALE, RoundingMode.HALF_UP);
            // Zero is a known cost here (free disposal); only null is unknown.
            BigDecimal unitCost = price == null ? null : price.unitCost();
            BigDecimal cost = entry.getValue() && unitCost != null ? quantity.multiply(unitCost).setScale(SCALE, RoundingMode.HALF_UP) : null;
            CostBasis lineBasis = !finished ? CostBasis.CURRENT : at == null || price == null ? CostBasis.ESTIMATED : price.costBasis();
            if (lineBasis == CostBasis.ESTIMATED) basis = CostBasis.ESTIMATED;
            if (cost == null) complete = false;
            else total = total.add(cost);
            lines.add(new RunWasteDisposalResponse.Line(id, item == null ? id : item.itemCode(), item == null ? null : item.itemName(),
                entry.getValue() ? quantity : null, item == null ? null : item.unitCode(), unitCost, cost, lineBasis));
        }
        lines.sort(Comparator.comparing(RunWasteDisposalResponse.Line::itemCode));
        return new RunWasteDisposalResponse(runId, total.setScale(SCALE, RoundingMode.HALF_UP), complete, lines, at, basis,
            basis == CostBasis.ESTIMATED);
    }
}
