package org.myweb.flowmat.domain.production.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.UnitConverter;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogUnitCostQuery;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogUnitCostView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogUnitCostView.CostBasis;
import org.myweb.flowmat.domain.production.api.dto.response.RunCostResponse;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRunItem;
import org.myweb.flowmat.domain.production.repository.ProductionRunItemRepository;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-time input cost: current prices while open, original end-time prices once finished (decision D+). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RunCostService {
    private static final int SCALE = 4;
    private final ProductionRunRepository productionRunRepository;
    private final ProductionRunItemRepository productionRunItemRepository;
    private final CatalogQuery catalogQuery;
    private final CatalogUnitCostQuery unitCosts;
    private final UnitConverter unitConverter;
    private final ProjectAccessService projectAccessService;

    public RunCostResponse cost(String productionRunId) {
        ProductionRun run = productionRunRepository.findByProductionRunIdAndDeletedYn(productionRunId, "N")
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        projectAccessService.requireProjectReadAccess(run.getProjectId());
        boolean finished = "finished".equalsIgnoreCase(run.getRunStatus());
        OffsetDateTime basisAt = finished ? run.getActualEndAt() : null;
        CostBasis basis = !finished ? CostBasis.CURRENT : basisAt == null ? CostBasis.ESTIMATED : CostBasis.HISTORICAL;
        List<ProductionRunItem> recordings = productionRunItemRepository.findAllByProductionRunIdOrderByProductionRunItemIdAsc(productionRunId)
            .stream().filter(row -> "input".equals(row.getDirection()) && !row.isCancelled() && row.getActualQty() != null).toList();
        List<String> ids = recordings.stream().map(ProductionRunItem::getItemId).distinct().toList();
        Map<String, CatalogItemView> items = catalogQuery.findItems(ids);
        Map<String, CatalogUnitCostView> prices = unitCosts.findUnitCostsAt(run.getProjectId(), ids, basisAt);
        Map<String, BigDecimal> quantities = new LinkedHashMap<>();
        Map<String, Boolean> convertible = new LinkedHashMap<>();
        for (ProductionRunItem recording : recordings) {
            String id = recording.getItemId();
            CatalogItemView item = items.get(id);
            convertible.putIfAbsent(id, true);
            if (item == null || !run.getProjectId().equals(item.projectId())) {
                convertible.put(id, false);
                continue; // Invalid historical references stay incomplete and reveal no other project's metadata.
            }
            try {
                BigDecimal quantity = unitConverter.toItemUnit(recording.getActualQty(), recording.getUnit(), item.unitId()).quantity();
                quantities.merge(id, quantity, BigDecimal::add);
            } catch (BusinessException exception) { convertible.put(id, false); }
        }
        List<RunCostResponse.Line> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        boolean complete = true;
        for (Map.Entry<String, Boolean> entry : convertible.entrySet()) {
            String id = entry.getKey();
            CatalogItemView item = items.get(id);
            if (item != null && !run.getProjectId().equals(item.projectId())) item = null;
            CatalogUnitCostView price = prices.get(id);
            BigDecimal quantity = quantities.getOrDefault(id, BigDecimal.ZERO).setScale(SCALE, RoundingMode.HALF_UP);
            BigDecimal unitCost = price != null && price.unitCost() != null && price.unitCost().signum() > 0 ? price.unitCost() : null;
            BigDecimal cost = entry.getValue() && unitCost != null ? quantity.multiply(unitCost).setScale(SCALE, RoundingMode.HALF_UP) : null;
            CostBasis lineBasis = !finished ? CostBasis.CURRENT : basisAt == null || price == null ? CostBasis.ESTIMATED : price.costBasis();
            if (lineBasis == CostBasis.ESTIMATED) basis = CostBasis.ESTIMATED;
            if (cost == null) complete = false;
            else total = total.add(cost);
            lines.add(new RunCostResponse.Line(id, item == null ? id : item.itemCode(), item == null ? null : item.itemName(),
                entry.getValue() ? quantity : null, item == null ? null : item.unitCode(), unitCost, cost, lineBasis));
        }
        BigDecimal output = run.getActualOutputQty();
        BigDecimal perUnit = complete && output != null && output.signum() > 0 ? total.divide(output, SCALE, RoundingMode.HALF_UP) : null;
        return new RunCostResponse(run.getProductionRunId(), total.setScale(SCALE, RoundingMode.HALF_UP), complete, output,
            perUnit, lines, basisAt, basis, basis == CostBasis.ESTIMATED);
    }
}
