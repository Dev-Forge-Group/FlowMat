package org.myweb.flowmat.domain.bom.application;

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
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.bom.api.dto.response.BomWhereUsedTreeResponse;
import org.myweb.flowmat.domain.bom.domain.entity.BomHeader;
import org.myweb.flowmat.domain.bom.domain.entity.BomLine;
import org.myweb.flowmat.domain.bom.repository.BomHeaderRepository;
import org.myweb.flowmat.domain.bom.repository.BomLineRepository;
import org.myweb.flowmat.domain.catalog.application.UnitConverter;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where an item is used at every level (docs/domain/multi-level-bom.md "다단계 역전개"). Follows approved BOMs only, the
 * same tree production and planning use: from the item to the approved BOMs that consume it, from their products to the
 * approved BOMs that consume those, up to the products no approved BOM uses. Along each route it multiplies how much of a
 * material one unit of its product needs (line quantity in the material's unit ÷ batch size in the product's unit), so a
 * top product says how much of the item one unit of it takes, all routes added up. By-product and waste lines are left
 * out: the BOM gives those off rather than using them.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BomWhereUsedTreeService {

    private static final int SHOWN_SCALE = 6;

    private final BomHeaderRepository bomHeaderRepository;
    private final BomLineRepository bomLineRepository;
    private final CatalogQuery catalogQuery;
    private final UnitConverter unitConverter;
    private final ProjectAccessService projectAccessService;
    private final ProjectCalendarQuery projectCalendar;

    /** One approved BOM line that consumes a material, with how much of it one unit of the product needs. */
    private record Edge(BomHeader header, BomLine line, BigDecimal rate) {
    }

    public BomWhereUsedTreeResponse allLevels(String projectId, String itemId) {
        return allLevels(projectId, itemId, null);
    }

    /** {@code on}: the day whose revisions are walked; null is the project's today (multi-level-bom.md M3, M7). */
    public BomWhereUsedTreeResponse allLevels(String projectId, String itemId, java.time.LocalDate on) {
        if (projectId == null || projectId.isBlank() || itemId == null || itemId.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "projectId and itemId are required.");
        }
        String project = projectId.trim();
        projectAccessService.requireProjectReadAccess(project);
        CatalogItemView start = catalogQuery.findProjectItem(project, itemId.trim())
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "Item does not exist in this project."));

        java.time.LocalDate day = on != null ? on : projectCalendar.today(project);
        Map<String, BomHeader> approvedByBom = BomTree.approvedByItem(bomHeaderRepository, project, day)
            .values().stream()
            .collect(Collectors.toMap(BomHeader::getBomId, Function.identity()));
        List<BomLine> bomLines = approvedByBom.isEmpty() ? List.of() : bomLineRepository.findAllByBomIdIn(approvedByBom.keySet());
        // Quantities add up through main lines that every batch uses: not a substitute, which stands in for one, nor an
        // optional material, which a work order may pick (ADR-032 decision 10, ADR-033 decision 7).
        Map<String, BomLine> substitutes = BomTree.mainLines(bomLines);
        List<BomLine> lines = bomLines.stream()
            .filter(line -> BomTree.isMaterial(line.getLineType()) && !substitutes.containsKey(line.getBomLineId())
                && !"Y".equals(line.getOptionalYn()))
            .toList();
        Set<String> itemIds = new HashSet<>();
        itemIds.add(start.itemId());
        approvedByBom.values().forEach(header -> itemIds.add(header.getTargetItemId()));
        lines.forEach(line -> itemIds.add(line.getChildItemId()));
        Map<String, CatalogItemView> items = catalogQuery.findItems(itemIds);

        List<String> problems = new ArrayList<>();
        Map<String, List<Edge>> usedBy = new HashMap<>();
        for (BomLine line : lines) {
            BomHeader header = approvedByBom.get(line.getBomId());
            usedBy.computeIfAbsent(line.getChildItemId(), id -> new ArrayList<>())
                .add(new Edge(header, line, rate(header, line, items, problems)));
        }
        Comparator<Edge> byProduct = Comparator.comparing(edge -> code(items, edge.header().getTargetItemId()));
        usedBy.values().forEach(edges -> edges.sort(byProduct));

        List<BomWhereUsedTreeResponse.Use> uses = new ArrayList<>();
        Map<String, TopTotal> tops = new LinkedHashMap<>();
        List<String> path = new ArrayList<>(List.of(code(items, start.itemId())));
        Set<String> onRoute = new HashSet<>(Set.of(start.itemId()));
        walk(start.itemId(), 1, BigDecimal.ONE, path, onRoute, usedBy, items, uses, tops);

        List<BomWhereUsedTreeResponse.TopProduct> topProducts = tops.entrySet().stream()
            .map(entry -> {
                CatalogItemView product = items.get(entry.getKey());
                TopTotal total = entry.getValue();
                return new BomWhereUsedTreeResponse.TopProduct(entry.getKey(), code(items, entry.getKey()),
                    product == null ? null : product.itemName(), unit(product), shown(total.perUnit), total.levels,
                    total.routes);
            })
            .sorted(Comparator.comparing(BomWhereUsedTreeResponse.TopProduct::itemCode))
            .toList();
        return new BomWhereUsedTreeResponse(start.itemId(), start.itemCode(), unit(start), uses, topProducts, problems, day);
    }

    private static final class TopTotal {
        private BigDecimal perUnit = BigDecimal.ZERO;
        private int levels;
        private int routes;
    }

    private void walk(
        String material,
        int level,
        BigDecimal perMaterialUnit,
        List<String> path,
        Set<String> onRoute,
        Map<String, List<Edge>> usedBy,
        Map<String, CatalogItemView> items,
        List<BomWhereUsedTreeResponse.Use> uses,
        Map<String, TopTotal> tops
    ) {
        for (Edge edge : usedBy.getOrDefault(material, List.of())) {
            String product = edge.header().getTargetItemId();
            // Approval keeps the tree free of loops and at most 10 deep; this only guards against bad data.
            if (onRoute.contains(product) || level > BomTree.MAX_LEVELS) {
                continue;
            }
            BigDecimal perProductUnit = perMaterialUnit == null || edge.rate() == null ? null : perMaterialUnit.multiply(edge.rate());
            boolean top = usedBy.getOrDefault(product, List.of()).isEmpty();
            path.add(code(items, product));
            CatalogItemView productItem = items.get(product);
            uses.add(new BomWhereUsedTreeResponse.Use(level, material, code(items, material), product, code(items, product),
                productItem == null ? null : productItem.itemName(), unit(productItem), edge.header().getBomId(),
                edge.header().getBomVersion(), edge.line().getQuantity(), edge.line().getUnit(), edge.header().getBaseQuantity(),
                edge.header().getBaseUnit(), shown(perProductUnit), top, List.copyOf(path)));
            if (top) {
                TopTotal total = tops.computeIfAbsent(product, id -> new TopTotal());
                total.perUnit = total.perUnit == null || perProductUnit == null ? null : total.perUnit.add(perProductUnit);
                total.levels = Math.max(total.levels, level);
                total.routes++;
            } else {
                onRoute.add(product);
                walk(product, level + 1, perProductUnit, path, onRoute, usedBy, items, uses, tops);
                onRoute.remove(product);
            }
            path.remove(path.size() - 1);
        }
    }

    /** Material per one unit of the product: line quantity in the material's unit ÷ batch size in the product's unit. */
    private BigDecimal rate(BomHeader header, BomLine line, Map<String, CatalogItemView> items, List<String> problems) {
        try {
            return BomTree.perProductUnit(unitConverter, header, line, unitId(items, line.getChildItemId()),
                unitId(items, header.getTargetItemId()));
        } catch (BusinessException e) {
            problems.add(code(items, header.getTargetItemId()) + " v" + header.getBomVersion() + ", material "
                + code(items, line.getChildItemId()) + ": " + e.getMessage());
            return null;
        }
    }

    private static BigDecimal shown(BigDecimal value) {
        return value == null ? null : value.setScale(SHOWN_SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private static String code(Map<String, CatalogItemView> items, String itemId) {
        CatalogItemView item = items.get(itemId);
        return item == null ? itemId : item.itemCode();
    }

    private static String unitId(Map<String, CatalogItemView> items, String itemId) {
        CatalogItemView item = items.get(itemId);
        return item == null ? null : item.unitId();
    }

    private static String unit(CatalogItemView item) {
        return item == null ? null : item.unitCode();
    }
}
