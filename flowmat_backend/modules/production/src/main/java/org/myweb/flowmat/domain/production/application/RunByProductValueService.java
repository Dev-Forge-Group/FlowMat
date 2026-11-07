package org.myweb.flowmat.domain.production.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.bom.application.publicapi.BomOutputQuery;
import org.myweb.flowmat.domain.catalog.application.UnitConverter;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogUnitCostQuery;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogUnitCostView.CostBasis;
import org.myweb.flowmat.domain.production.api.dto.response.RunByProductValueResponse;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRunItem;
import org.myweb.flowmat.domain.production.repository.ProductionRunItemRepository;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Values actual uncancelled by-products in the pinned BOM, using the same D+ price basis as material cost. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RunByProductValueService {
    private static final int SCALE = 4;
    private final ProductionRunRepository runs;
    private final ProductionRunItemRepository recordings;
    private final BomOutputQuery bomOutputs;
    private final CatalogQuery catalog;
    private final CatalogUnitCostQuery unitCosts;
    private final UnitConverter units;
    private final ProjectAccessService access;

    public RunByProductValueResponse value(String runId) {
        var run = runs.findByProductionRunIdAndDeletedYn(runId, "N")
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        access.requireProjectReadAccess(run.getProjectId());
        boolean finished = "finished".equalsIgnoreCase(run.getRunStatus());
        OffsetDateTime at = finished ? run.getActualEndAt() : null;
        CostBasis basis = !finished ? CostBasis.CURRENT : at == null ? CostBasis.ESTIMATED : CostBasis.HISTORICAL;
        var eligible = bomOutputs.findByProductItemIds(run.getProjectId(), run.getBomId());
        var rows = recordings.findAllByProductionRunIdOrderByProductionRunItemIdAsc(runId).stream()
            .filter(row -> "output".equals(row.getDirection()) && !row.isCancelled() && row.getActualQty() != null
                && eligible.contains(row.getItemId())).toList();
        var ids = rows.stream().map(ProductionRunItem::getItemId).distinct().toList();
        var items = catalog.findItems(ids);
        var prices = unitCosts.findUnitCostsAt(run.getProjectId(), ids, at);
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
            } catch (BusinessException error) { convertible.put(id, false); }
        }
        var lines = new ArrayList<RunByProductValueResponse.Line>();
        BigDecimal total = BigDecimal.ZERO;
        boolean complete = true;
        for (var entry : convertible.entrySet()) {
            String id = entry.getKey();
            var item = items.get(id);
            if (item != null && !run.getProjectId().equals(item.projectId())) item = null;
            var price = prices.get(id);
            BigDecimal quantity = quantities.getOrDefault(id, BigDecimal.ZERO).setScale(SCALE, RoundingMode.HALF_UP);
            BigDecimal unitCost = price != null && price.unitCost() != null && price.unitCost().signum() > 0 ? price.unitCost() : null;
            BigDecimal value = entry.getValue() && unitCost != null ? quantity.multiply(unitCost).setScale(SCALE, RoundingMode.HALF_UP) : null;
            CostBasis lineBasis = !finished ? CostBasis.CURRENT : at == null || price == null ? CostBasis.ESTIMATED : price.costBasis();
            if (lineBasis == CostBasis.ESTIMATED) basis = CostBasis.ESTIMATED;
            if (value == null) complete = false;
            else total = total.add(value);
            lines.add(new RunByProductValueResponse.Line(id, item == null ? id : item.itemCode(), item == null ? null : item.itemName(),
                entry.getValue() ? quantity : null, item == null ? null : item.unitCode(), unitCost, value, lineBasis));
        }
        lines.sort(java.util.Comparator.comparing(RunByProductValueResponse.Line::itemCode));
        return new RunByProductValueResponse(runId, total.setScale(SCALE, RoundingMode.HALF_UP), complete, lines, at, basis,
            basis == CostBasis.ESTIMATED);
    }
}
