package org.myweb.flowmat.domain.bom.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.myweb.flowmat.domain.bom.domain.entity.BomHeader;
import org.myweb.flowmat.domain.bom.domain.entity.BomLine;
import org.myweb.flowmat.domain.bom.domain.enums.BomStatus;
import org.myweb.flowmat.domain.bom.repository.BomHeaderRepository;
import org.myweb.flowmat.domain.bom.repository.BomLineRepository;
import org.myweb.flowmat.domain.catalog.application.UnitConverter;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;

/**
 * The project's approved BOMs as a tree of items (docs/domain/multi-level-bom.md): on a given day an item is made from the
 * materials of its approved revision effective that day, which may have approved BOMs of their own. Approval keeps every
 * approved revision free of loops and at most {@link #MAX_LEVELS} deep; the walks here still stop at that depth.
 */
public final class BomTree {

    /** The deepest a BOM may go through its materials' BOMs. */
    public static final int MAX_LEVELS = 10;
    /** A line the BOM consumes; the other line types are what a batch gives off (docs/domain/bom-by-products.md). */
    public static final String MATERIAL = "material";
    public static final Set<String> LINE_TYPES = Set.of(MATERIAL, "by_product", "waste");
    /** The most a material line may plan to lose: 1 plans twice the net quantity (ADR-031 decision 2). */
    public static final BigDecimal MAX_SCRAP_RATE = BigDecimal.ONE;
    /** A substitute group's main line first: lowest priority, then sort order, then id (ADR-032 decision 1). */
    public static final Comparator<BomLine> PREFERENCE = Comparator
        .comparing((BomLine line) -> line.getPriority() == null ? 0 : line.getPriority())
        .thenComparing(line -> line.getSortOrder() == null ? 0 : line.getSortOrder())
        .thenComparing(BomLine::getBomLineId);
    private static final String NOT_DELETED = "N";

    private BomTree() {
    }

    /**
     * Item → its approved revision effective on {@code on}, a project calendar day: what the item is made from that day
     * (docs/domain/multi-level-bom.md M3). Approved revisions of one item do not overlap, so there is at most one; legacy
     * overlapping rows take the highest revision. An item whose approved periods all miss the day has none.
     */
    public static Map<String, BomHeader> approvedByItem(BomHeaderRepository headers, String projectId, LocalDate on) {
        return headers.findAllByProjectIdAndBomStatusAndDeletedYn(projectId, BomStatus.APPROVED.code(), NOT_DELETED).stream()
            .filter(header -> covers(header, on))
            .collect(Collectors.toMap(BomHeader::getTargetItemId, Function.identity(),
                (one, other) -> Comparator.comparing(BomHeader::getBomVersion).compare(one, other) >= 0 ? one : other));
    }

    /** Whether the revision's effective period includes the day; both ends count and a missing end is open. */
    public static boolean covers(BomHeader header, LocalDate on) {
        return (header.getEffectiveFrom() == null || !on.isBefore(header.getEffectiveFrom()))
            && (header.getEffectiveTo() == null || !on.isAfter(header.getEffectiveTo()));
    }

    /**
     * Item → the materials of any of its approved revisions, whatever their periods, so loops and depth hold on every day.
     * {@code checkedItemId}'s revisions are left out: a revision of that item is being checked.
     */
    public static Map<String, List<String>> approvedChildren(
        BomHeaderRepository headers,
        BomLineRepository lines,
        String projectId,
        String checkedItemId
    ) {
        Map<String, String> itemByBom = headers.findAllByProjectIdAndBomStatusAndDeletedYn(projectId, BomStatus.APPROVED.code(),
                NOT_DELETED).stream()
            .filter(header -> !header.getTargetItemId().equals(checkedItemId))
            .collect(Collectors.toMap(BomHeader::getBomId, BomHeader::getTargetItemId));
        Map<String, List<String>> tree = new HashMap<>();
        if (itemByBom.isEmpty()) {
            return tree;
        }
        for (BomLine line : lines.findAllByBomIdIn(List.copyOf(itemByBom.keySet()))) {
            if (!isMaterial(line.getLineType())) {
                continue;
            }
            List<String> children = tree.computeIfAbsent(itemByBom.get(line.getBomId()), item -> new ArrayList<>());
            if (!children.contains(line.getChildItemId())) {
                children.add(line.getChildItemId());
            }
        }
        return tree;
    }

    /**
     * How much of a line's material one unit of the BOM's product takes: the line quantity in the material's unit, with the
     * line's planned scrap (ADR-031), ÷ the batch size in the product's unit, 12 decimals. The unit ids are the material's
     * and the product's (null when unknown).
     * Throws a 400 when a quantity cannot be converted or the batch is not above zero.
     */
    public static BigDecimal perProductUnit(
        UnitConverter converter,
        BomHeader header,
        BomLine line,
        String materialUnitId,
        String productUnitId
    ) {
        BigDecimal quantity = converter.toItemUnit(line.getQuantity(), line.getUnit(), materialUnitId).quantity();
        BigDecimal batch = converter.toItemUnit(header.getBaseQuantity(), header.getBaseUnit(), productUnitId).quantity();
        if (quantity == null || batch == null || batch.signum() <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Base quantity must be greater than 0.");
        }
        BigDecimal planned = isMaterial(line.getLineType()) ? quantity.multiply(scrapFactor(line)) : quantity;
        return planned.divide(batch, 12, RoundingMode.HALF_UP);
    }

    /** Lines written before line types were used have none; they are materials. */
    public static boolean isMaterial(String lineType) {
        return lineType == null || MATERIAL.equals(lineType);
    }

    /** 1 + the line's scrap rate: what the line plans to use for each unit that ends up in the product (ADR-031 decision 1). */
    public static BigDecimal scrapFactor(BomLine line) {
        BigDecimal rate = line.getScrapRate();
        return rate == null || rate.signum() == 0 ? BigDecimal.ONE : BigDecimal.ONE.add(rate);
    }

    /**
     * Why a line cannot have this scrap rate, or null when it can: from 0 to {@link #MAX_SCRAP_RATE} with at most 4 decimals,
     * and only on a material line (ADR-031 decisions 2-3). Starts in lower case, for "Material X: ..." and a sentence alike.
     */
    public static String scrapRateProblem(BigDecimal rate, String lineType) {
        if (rate == null || rate.signum() == 0) {
            return null;
        }
        if (rate.signum() < 0 || rate.compareTo(MAX_SCRAP_RATE) > 0) {
            return "a scrap rate must be from 0 to 1 (0% to 100%)";
        }
        if (rate.stripTrailingZeros().scale() > 4) {
            return "a scrap rate takes at most 4 decimals (0.01%)";
        }
        if (!isMaterial(lineType)) {
            return "only a material line can have a scrap rate";
        }
        return null;
    }

    /**
     * Substitute line id → the main line it stands in for (ADR-032 decision 1). The lines of one revision that share a
     * substitute group form the group, and {@link #PREFERENCE} puts its main line first. Main lines and lines outside a
     * group are not keys. The lines may come from several revisions.
     */
    public static Map<String, BomLine> mainLines(Collection<BomLine> lines) {
        Map<List<String>, List<BomLine>> groups = new HashMap<>();
        for (BomLine line : lines) {
            if (line.getSubstituteGroup() != null) {
                groups.computeIfAbsent(List.of(line.getBomId(), line.getSubstituteGroup()), key -> new ArrayList<>()).add(line);
            }
        }
        Map<String, BomLine> main = new HashMap<>();
        for (List<BomLine> group : groups.values()) {
            group.sort(PREFERENCE);
            group.subList(1, group.size()).forEach(line -> main.put(line.getBomLineId(), group.get(0)));
        }
        return main;
    }

    /** A way from {@code from} down to {@code to} through approved BOMs, both ends included; null when there is none. */
    public static List<String> pathTo(String from, String to, Map<String, List<String>> tree) {
        List<String> path = new ArrayList<>();
        path.add(from);
        return walk(from, to, tree, path, new HashSet<>()) ? path : null;
    }

    private static boolean walk(String at, String to, Map<String, List<String>> tree, List<String> path, Set<String> seen) {
        if (at.equals(to)) {
            return true;
        }
        if (!seen.add(at) || path.size() > MAX_LEVELS + 1) {
            return false;
        }
        for (String child : tree.getOrDefault(at, List.of())) {
            path.add(child);
            if (walk(child, to, tree, path, seen)) {
                return true;
            }
            path.remove(path.size() - 1);
        }
        return false;
    }

    /** Levels of approved BOMs below an item: 0 for a bought item, 1 for an item made only from bought ones. */
    public static int depth(String itemId, Map<String, List<String>> tree) {
        return depth(itemId, tree, new HashMap<>(), 0);
    }

    private static int depth(String itemId, Map<String, List<String>> tree, Map<String, Integer> known, int reached) {
        Integer cached = known.get(itemId);
        if (cached != null) {
            return cached;
        }
        List<String> children = tree.get(itemId);
        if (children == null || children.isEmpty() || reached > MAX_LEVELS) {
            return 0;
        }
        int deepest = 0;
        for (String child : children) {
            deepest = Math.max(deepest, depth(child, tree, known, reached + 1));
        }
        known.put(itemId, deepest + 1);
        return deepest + 1;
    }

    /**
     * Low-level code of every item in the tree: the lowest level it appears on under any item (0 at the top). Planning
     * works through items in this order, so an item's demand is complete before its own BOM is exploded.
     */
    public static Map<String, Integer> lowLevelCodes(Map<String, List<String>> tree) {
        Map<String, Integer> codes = new HashMap<>();
        tree.forEach((parent, children) -> {
            codes.putIfAbsent(parent, 0);
            children.forEach(child -> codes.putIfAbsent(child, 0));
        });
        for (int round = 0; round <= MAX_LEVELS; round++) {
            boolean changed = false;
            for (Map.Entry<String, List<String>> entry : tree.entrySet()) {
                int below = codes.get(entry.getKey()) + 1;
                for (String child : entry.getValue()) {
                    if (codes.get(child) < below && below <= MAX_LEVELS + 1) {
                        codes.put(child, below);
                        changed = true;
                    }
                }
            }
            if (!changed) {
                break;
            }
        }
        return codes;
    }
}
