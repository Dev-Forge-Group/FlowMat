package org.myweb.flowmat.domain.bom.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.bom.api.dto.response.BomCostRollupResponse;
import org.myweb.flowmat.domain.bom.domain.entity.BomHeader;
import org.myweb.flowmat.domain.bom.domain.entity.BomLine;
import org.myweb.flowmat.domain.bom.repository.BomHeaderRepository;
import org.myweb.flowmat.domain.bom.repository.BomLineRepository;
import org.myweb.flowmat.domain.catalog.application.publicapi.UnitConversion;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Material cost per unit of every item with an approved BOM, rolled up from the bottom of the approved tree
 * (docs/domain/multi-level-bom.md "반제품 원가 누적"). A bought material costs its unit cost; a made one costs its own
 * roll-up, not its stored unit cost, so a change deep down shows at every level above it. Each line adds what one unit of
 * the product takes of the material (line quantity in the material's unit ÷ batch in the product's unit) times that
 * cost. By-product and waste lines carry no credit. Read only.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BomCostRollupService {

    private static final int SCALE = 4;

    private final BomHeaderRepository bomHeaderRepository;
    private final BomLineRepository bomLineRepository;
    private final CatalogQuery catalogQuery;
    private final UnitConversion unitConverter;
    private final ProjectAccess projectAccessService;
    private final ProjectCalendarQuery projectCalendar;

    /** One item's roll-up: the cost of what is known, and what is not. */
    private record Cost(BigDecimal value, Set<String> missing, List<String> problems) {

        boolean complete() {
            return missing.isEmpty() && problems.isEmpty();
        }
    }

    public BomCostRollupResponse rollup(String projectId) {
        return rollup(projectId, null);
    }

    /** {@code on}: the day whose revisions are rolled up; null is the project's today (multi-level-bom.md M3, M7). */
    public BomCostRollupResponse rollup(String projectId, java.time.LocalDate on) {
        if (projectId == null || projectId.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "projectId is required.");
        }
        String project = projectId.trim();
        projectAccessService.requireProjectReadAccess(project);
        java.time.LocalDate day = on != null ? on : projectCalendar.today(project);
        Map<String, BomHeader> approved = BomTree.approvedByItem(bomHeaderRepository, project, day);
        if (approved.isEmpty()) {
            return new BomCostRollupResponse(List.of(), day);
        }
        Map<String, String> itemByBom = approved.values().stream()
            .collect(Collectors.toMap(BomHeader::getBomId, BomHeader::getTargetItemId));
        List<BomLine> bomLines = bomLineRepository.findAllByBomIdIn(itemByBom.keySet());
        // A substitute stands in for its main line and an optional material waits for a work order to pick it: the rollup
        // costs the main lines without the optional ones (ADR-032 decision 5, ADR-033 decision 7).
        Map<String, BomLine> substitutes = BomTree.mainLines(bomLines);
        Map<String, List<BomLine>> linesByItem = bomLines.stream()
            .filter(line -> BomTree.isMaterial(line.getLineType()) && !substitutes.containsKey(line.getBomLineId())
                && !"Y".equals(line.getOptionalYn()))
            .collect(Collectors.groupingBy(line -> itemByBom.get(line.getBomId())));
        Set<String> itemIds = new HashSet<>(approved.keySet());
        linesByItem.values().forEach(lines -> lines.forEach(line -> itemIds.add(line.getChildItemId())));
        Map<String, CatalogItemView> items = catalogQuery.findItems(itemIds);
        Map<String, List<String>> tree = new HashMap<>();
        linesByItem.forEach((item, lines) -> tree.put(item, lines.stream().map(BomLine::getChildItemId).toList()));

        Map<String, Cost> known = new HashMap<>();
        List<BomCostRollupResponse.Line> result = new ArrayList<>();
        for (Map.Entry<String, BomHeader> entry : approved.entrySet()) {
            CatalogItemView item = items.get(entry.getKey());
            Cost cost = cost(entry.getKey(), approved, linesByItem, items, known, new HashSet<>());
            BigDecimal current = unitCost(item);
            result.add(new BomCostRollupResponse.Line(entry.getKey(), code(items, entry.getKey()), item == null ? null : item.itemName(),
                item == null ? null : item.unitCode(), entry.getValue().getBomId(),
                entry.getValue().getBomVersion(), BomTree.depth(entry.getKey(), tree), cost.value().setScale(SCALE, RoundingMode.HALF_UP),
                cost.complete(), List.copyOf(cost.missing()), cost.problems(), current));
        }
        result.sort(Comparator.comparingInt(BomCostRollupResponse.Line::levels)
            .thenComparing(BomCostRollupResponse.Line::itemCode, Comparator.nullsLast(Comparator.naturalOrder())));
        return new BomCostRollupResponse(result, day);
    }

    /** Cost of one unit of a made item; memoised, since a sub-assembly can sit under many products. */
    private Cost cost(
        String itemId,
        Map<String, BomHeader> approved,
        Map<String, List<BomLine>> linesByItem,
        Map<String, CatalogItemView> items,
        Map<String, Cost> known,
        Set<String> path
    ) {
        Cost cached = known.get(itemId);
        if (cached != null) {
            return cached;
        }
        BomHeader header = approved.get(itemId);
        BigDecimal total = BigDecimal.ZERO;
        Set<String> missing = new TreeSet<>();
        List<String> problems = new ArrayList<>();
        path.add(itemId);
        for (BomLine line : linesByItem.getOrDefault(itemId, List.of())) {
            String child = line.getChildItemId();
            BigDecimal rate;
            try {
                rate = BomTree.perProductUnit(unitConverter, header, line, unitId(items, child), unitId(items, itemId));
            } catch (BusinessException e) {
                problems.add(code(items, itemId) + " v" + header.getBomVersion() + ", material " + code(items, child) + ": " + e.getMessage());
                continue;
            }
            // Approval keeps loops out and the tree at most 10 deep; the path check only guards against bad data.
            if (approved.containsKey(child) && !path.contains(child) && path.size() <= BomTree.MAX_LEVELS) {
                Cost below = cost(child, approved, linesByItem, items, known, path);
                total = total.add(rate.multiply(below.value()));
                missing.addAll(below.missing());
                problems.addAll(below.problems());
            } else {
                BigDecimal unitCost = unitCost(items.get(child));
                if (unitCost == null) {
                    missing.add(code(items, child));
                } else {
                    total = total.add(rate.multiply(unitCost));
                }
            }
        }
        path.remove(itemId);
        Cost cost = new Cost(total, missing, problems);
        known.put(itemId, cost);
        return cost;
    }

    private static String code(Map<String, CatalogItemView> items, String itemId) {
        CatalogItemView item = items.get(itemId);
        return item == null ? itemId : item.itemCode();
    }

    private static String unitId(Map<String, CatalogItemView> items, String itemId) {
        CatalogItemView item = items.get(itemId);
        return item == null ? null : item.unitId();
    }

    /** The stored unit cost when it is above zero; zero or none means not known. */
    private static BigDecimal unitCost(CatalogItemView item) {
        return item == null || item.unitCost() == null || item.unitCost().signum() <= 0 ? null : item.unitCost();
    }
}
