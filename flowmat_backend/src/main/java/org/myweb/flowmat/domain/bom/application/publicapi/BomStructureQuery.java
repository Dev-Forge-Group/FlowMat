package org.myweb.flowmat.domain.bom.application.publicapi;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The project's approved BOM structure for other bounded contexts, which use this instead of the BOM repositories
 * (docs/architecture/adr/ADR-002-module-dependency.md). Add an operation only when a caller needs it.
 */
public interface BomStructureQuery {

    /** Item → the id of its approved revision effective on {@code on}, a project calendar day (docs/domain/multi-level-bom.md M3). */
    Map<String, String> approvedBomIdByItem(String projectId, LocalDate on);

    /** Item → the materials of any of its approved revisions, whatever their periods, for loops and low-level codes. */
    Map<String, List<String>> approvedChildren(String projectId);

    /** The items of a revision's lines in the order the BOM lists them; empty for an unknown revision. */
    List<String> lineItemIds(String bomId);
}
