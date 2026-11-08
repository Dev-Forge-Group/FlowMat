package org.myweb.flowmat.domain.bom.application.publicapi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.myweb.flowmat.domain.bom.application.BomTree;

/**
 * The project's approved BOM structure for other bounded contexts, which use this instead of the BOM repositories
 * (docs/architecture/adr/ADR-002-module-dependency.md). Add an operation only when a caller needs it.
 */
public interface BomStructureQuery {

    /** Item → the id of its approved revision effective on {@code on}, a project calendar day (docs/domain/multi-level-bom.md M3). */
    Map<String, String> approvedBomIdByItem(String projectId, LocalDate on);

    /** Item → the materials of any of its approved revisions, whatever their periods, for loops and low-level codes. */
    Map<String, List<String>> approvedChildren(String projectId);

    /**
     * Low-level code of every item in a tree such as {@link #approvedChildren}: the lowest level it appears on under any
     * item (0 at the top). Planning works through items in this order, so an item's demand is complete before its own BOM
     * is exploded.
     */
    static Map<String, Integer> lowLevelCodes(Map<String, List<String>> tree) {
        return BomTree.lowLevelCodes(tree);
    }

    /** The items of a revision's lines in the order the BOM lists them; empty for an unknown revision. */
    List<String> lineItemIds(String bomId);

    /**
     * A revision's own substitutes (ADR-032 decision 8): substitute item → the main item it stands in for and how much of
     * the main material, in the main item's unit, one unit of it stands for. Empty for an unknown revision or one without
     * substitutes; a line whose quantity cannot be converted is left out.
     */
    Map<String, SubstituteShare> substituteShares(String bomId);

    record SubstituteShare(String mainItemId, BigDecimal mainPerUnit) {
    }

    /**
     * The items of a revision's optional material lines and of those inside the phantoms it goes through, by their
     * approved revisions effective on {@code on}, a project calendar day; null looks at the revision's own lines only
     * (ADR-033 decision 5). Empty for an unknown revision.
     */
    java.util.Set<String> optionalItemIds(String bomId, LocalDate on);
}
