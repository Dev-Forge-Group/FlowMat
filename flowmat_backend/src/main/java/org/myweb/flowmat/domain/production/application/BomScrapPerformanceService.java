package org.myweb.flowmat.domain.production.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.bom.api.dto.response.BomLineResponse;
import org.myweb.flowmat.domain.bom.api.dto.response.BomResponse;
import org.myweb.flowmat.domain.bom.application.BomService;
import org.myweb.flowmat.domain.bom.application.BomTree;
import org.myweb.flowmat.domain.bom.application.publicapi.BomStructureQuery;
import org.myweb.flowmat.domain.catalog.application.UnitConverter;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.production.api.dto.response.BomScrapPerformanceResponse;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRunItem;
import org.myweb.flowmat.domain.production.repository.ProductionRunItemRepository;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a BOM revision's finished runs lost against its planned scrap, material by material
 * (docs/architecture/adr/ADR-034-bom-scrap-performance.md). Each run's plan rows of the revision's own materials are scaled
 * to its actual output; the net is that without the line's scrap, and the actual is what went in, a substitute as its main
 * material (ADR-032). Read only.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BomScrapPerformanceService {

    private static final int SCALE = 4;
    private static final String NOT_DELETED = "N";

    private final BomService bomService;
    private final BomStructureQuery bomStructure;
    private final ProductionRunRepository productionRunRepository;
    private final ProductionRunItemRepository productionRunItemRepository;
    private final CatalogQuery catalogQuery;
    private final UnitConverter unitConverter;

    /** Running totals of one material. */
    private static final class Total {
        private BigDecimal net = BigDecimal.ZERO;
        private BigDecimal planned = BigDecimal.ZERO;
        private BigDecimal actual = BigDecimal.ZERO;
        private int runs;
        private boolean complete = true;
    }

    public BomScrapPerformanceResponse performance(String bomId) {
        // Checks read access to the BOM's project.
        BomResponse bom = bomService.getBom(bomId);
        Map<String, BigDecimal> scrapRates = new HashMap<>();
        for (BomLineResponse line : bom.lines()) {
            if (BomTree.isMaterial(line.lineType()) && !line.phantom() && line.substituteFor() == null) {
                scrapRates.put(line.childItemId(), line.scrapRate() == null ? BigDecimal.ZERO : line.scrapRate());
            }
        }
        Map<String, BomStructureQuery.SubstituteShare> shares = bomStructure.substituteShares(bomId);
        List<ProductionRun> runs = productionRunRepository.findAllByBomIdAndDeletedYn(bomId, NOT_DELETED).stream()
            .filter(run -> run.affectsPhysicalState() && "finished".equalsIgnoreCase(run.getRunStatus()))
            .filter(run -> positive(run.getActualOutputQty()) && positive(run.getPlannedOutputQty()))
            .toList();

        Map<String, Total> totals = new LinkedHashMap<>();
        Map<String, CatalogItemView> items = new HashMap<>();
        for (ProductionRun run : runs) {
            BigDecimal ratio = run.getActualOutputQty().divide(run.getPlannedOutputQty(), SCALE + 8, RoundingMode.HALF_UP);
            List<ProductionRunItem> recordings = productionRunItemRepository.findAllByProductionRunIdOrderByProductionRunItemIdAsc(
                run.getProductionRunId());
            Map<String, BigDecimal> used = new HashMap<>();
            Set<String> unconvertible = new HashSet<>();
            for (ProductionRunItem recording : recordings) {
                if (!"input".equals(recording.getDirection()) || recording.isCancelled() || "bom".equals(recording.getQuantitySource())
                    || recording.getActualQty() == null) {
                    continue;
                }
                CatalogItemView item = items.computeIfAbsent(recording.getItemId(), id -> catalogQuery.findItems(List.of(id)).get(id));
                BomStructureQuery.SubstituteShare share = shares.get(recording.getItemId());
                String counted = share != null ? share.mainItemId() : recording.getItemId();
                try {
                    BigDecimal quantity = unitConverter.toItemUnit(recording.getActualQty(), recording.getUnit(),
                        item == null ? null : item.unitId()).quantity();
                    used.merge(counted, share != null ? quantity.multiply(share.mainPerUnit()) : quantity, BigDecimal::add);
                } catch (BusinessException e) {
                    unconvertible.add(counted);
                }
            }
            for (ProductionRunItem plan : recordings) {
                BigDecimal scrapRate = scrapRates.get(plan.getItemId());
                // Plan rows of the revision's own materials; those through a phantom come from another revision's lines.
                if (!"bom".equals(plan.getQuantitySource()) || plan.getViaItemId() != null || scrapRate == null
                    || plan.getPlannedQty() == null) {
                    continue;
                }
                BigDecimal planned = plan.getPlannedQty().multiply(ratio);
                Total total = totals.computeIfAbsent(plan.getItemId(), id -> new Total());
                total.planned = total.planned.add(planned);
                total.net = total.net.add(planned.divide(BigDecimal.ONE.add(scrapRate), SCALE + 8, RoundingMode.HALF_UP));
                total.actual = total.actual.add(used.getOrDefault(plan.getItemId(), BigDecimal.ZERO));
                total.runs++;
                total.complete = total.complete && !unconvertible.contains(plan.getItemId());
            }
        }

        Map<String, CatalogItemView> named = catalogQuery.findItems(totals.keySet());
        List<BomScrapPerformanceResponse.Line> lines = new ArrayList<>();
        for (BomLineResponse line : bom.lines()) {
            Total total = totals.get(line.childItemId());
            if (total == null || line.substituteFor() != null) {
                continue;
            }
            CatalogItemView item = named.get(line.childItemId());
            BigDecimal net = scale(total.net);
            BigDecimal actual = scale(total.actual);
            BigDecimal lossRate = net.signum() == 0 ? null : actual.divide(net, SCALE, RoundingMode.HALF_UP).subtract(BigDecimal.ONE);
            lines.add(new BomScrapPerformanceResponse.Line(line.childItemId(), item == null ? line.childItemId() : item.itemCode(),
                item == null ? null : item.unitCode(), scrapRates.get(line.childItemId()), total.runs, net, scale(total.planned), actual,
                lossRate, total.complete));
        }
        return new BomScrapPerformanceResponse(bom.bomId(), bom.bomVersion(), runs.size(), lines);
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
