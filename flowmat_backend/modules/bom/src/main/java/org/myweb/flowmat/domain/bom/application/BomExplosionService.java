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
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.bom.api.dto.response.BomExplosionResponse;
import org.myweb.flowmat.domain.bom.api.dto.response.BomRequirementResponse;
import org.myweb.flowmat.domain.bom.domain.entity.BomHeader;
import org.myweb.flowmat.domain.bom.repository.BomHeaderRepository;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;

/**
 * Explodes an approved BOM through its materials' approved BOMs (docs/domain/multi-level-bom.md). Each level is the
 * ordinary one-level calculation ({@link BomService#requirementsForRun}), fed the quantity the level above needs, so units
 * and rounding match what a run of that sub-assembly would plan. Read only; it has no transaction, so a sub-assembly whose
 * BOM cannot be worked out is reported instead of rolling anything back.
 */
@Service
@RequiredArgsConstructor
public class BomExplosionService {

    private static final String NOT_DELETED = "N";
    private static final int SCALE = 4;

    private final BomHeaderRepository bomHeaderRepository;
    private final BomService bomService;
    private final CatalogQuery catalogQuery;
    private final ProjectAccessService projectAccessService;
    private final ProjectCalendarQuery projectCalendar;

    public BomExplosionResponse explode(String bomId, String quantityText) {
        return explode(bomId, quantityText, null);
    }

    /** {@code on}: the day whose revisions the levels below use; null is the project's today (multi-level-bom.md M3, M7). */
    public BomExplosionResponse explode(String bomId, String quantityText, java.time.LocalDate on) {
        BomHeader header = bomHeaderRepository.findByBomIdAndDeletedYn(bomId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        projectAccessService.requireProjectReadAccess(header.getProjectId());
        BigDecimal quantity = quantity(quantityText);
        java.time.LocalDate day = on != null ? on : projectCalendar.today(header.getProjectId());
        BomRequirementResponse top = bomService.requirementsForRun(bomId, header.getProjectId(), null, quantity, day);

        Walk walk = new Walk(header.getProjectId(), day,
            BomTree.approvedByItem(bomHeaderRepository, header.getProjectId(), day));
        Set<String> path = new HashSet<>();
        path.add(header.getTargetItemId());
        walk.level(top, header.getTargetItemId(), 1, path);

        Set<String> itemIds = new HashSet<>(walk.bought.keySet());
        walk.lines.forEach(line -> itemIds.add(line.itemId()));
        itemIds.add(header.getTargetItemId());
        Map<String, CatalogItemView> items = catalogQuery.findItems(itemIds);

        List<BomExplosionResponse.Line> lines = walk.lines.stream()
            .map(line -> new BomExplosionResponse.Line(line.level(), line.parentItemId(), line.itemId(), code(items, line.itemId()),
                name(items, line.itemId()), scale(line.quantity()), line.unit(), line.bomId(), line.bomVersion()))
            .toList();
        BigDecimal cost = BigDecimal.ZERO;
        boolean complete = true;
        List<BomExplosionResponse.Material> materials = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : walk.bought.entrySet()) {
            CatalogItemView item = items.get(entry.getKey());
            BigDecimal unitCost = item != null && item.unitCost() != null && item.unitCost().signum() > 0 ? item.unitCost() : null;
            BigDecimal lineCost = unitCost == null ? null : scale(entry.getValue().multiply(unitCost));
            if (lineCost == null) {
                complete = false;
            } else {
                cost = cost.add(lineCost);
            }
            materials.add(new BomExplosionResponse.Material(entry.getKey(), code(items, entry.getKey()), name(items, entry.getKey()),
                scale(entry.getValue()), walk.units.get(entry.getKey()), unitCost, lineCost));
        }
        materials.sort(Comparator.comparing(BomExplosionResponse.Material::itemCode, Comparator.nullsLast(Comparator.naturalOrder())));
        return new BomExplosionResponse(header.getBomId(), header.getBomVersion(), header.getTargetItemId(),
            code(items, header.getTargetItemId()), quantity, walk.deepest, lines, materials, scale(cost), complete, walk.problems,
            day);
    }

    private record Node(int level, String parentItemId, String itemId, BigDecimal quantity, String unit, String bomId, Integer bomVersion) {
    }

    /** One explosion's state: the tree so far, bought materials added up, and the deepest level. */
    private final class Walk {
        private final String projectId;
        private final java.time.LocalDate day;
        private final Map<String, BomHeader> approved;
        private final List<Node> lines = new ArrayList<>();
        private final Map<String, BigDecimal> bought = new LinkedHashMap<>();
        private final Map<String, String> units = new HashMap<>();
        private final List<String> problems = new ArrayList<>();
        private int deepest;

        private Walk(String projectId, java.time.LocalDate day, Map<String, BomHeader> approved) {
            this.projectId = projectId;
            this.day = day;
            this.approved = approved;
        }

        private void level(BomRequirementResponse requirement, String parentItemId, int level, Set<String> path) {
            deepest = Math.max(deepest, level);
            for (BomRequirementResponse.Line line : requirement.lines()) {
                String child = line.childItemId();
                BomHeader own = approved.get(child);
                // Approval keeps loops out; the path check and the level limit only guard against a tree changed meanwhile.
                boolean madeHere = own != null && !path.contains(child) && level < BomTree.MAX_LEVELS;
                lines.add(new Node(level, parentItemId, child, line.requiredItemQuantity(), line.itemUnit(),
                    madeHere ? own.getBomId() : null, madeHere ? own.getBomVersion() : null));
                if (!madeHere || line.requiredItemQuantity().signum() <= 0) {
                    bought.merge(child, line.requiredItemQuantity(), BigDecimal::add);
                    units.putIfAbsent(child, line.itemUnit());
                    continue;
                }
                BomRequirementResponse below;
                try {
                    below = bomService.requirementsForRun(own.getBomId(), projectId, child, line.requiredItemQuantity(), day);
                } catch (BusinessException exception) {
                    problems.add(child + ": " + exception.getMessage());
                    continue;
                }
                Set<String> deeper = new HashSet<>(path);
                deeper.add(child);
                level(below, child, level + 1, deeper);
            }
        }
    }

    private static BigDecimal quantity(String text) {
        if (text == null || text.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "quantity is required.");
        }
        BigDecimal quantity;
        try {
            quantity = new BigDecimal(text.trim());
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "quantity must be a number.");
        }
        if (quantity.signum() <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "quantity must be greater than 0.");
        }
        return quantity;
    }

    private static String code(Map<String, CatalogItemView> items, String itemId) {
        CatalogItemView item = items.get(itemId);
        return item == null ? itemId : item.itemCode();
    }

    private static String name(Map<String, CatalogItemView> items, String itemId) {
        CatalogItemView item = items.get(itemId);
        return item == null ? null : item.itemName();
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
