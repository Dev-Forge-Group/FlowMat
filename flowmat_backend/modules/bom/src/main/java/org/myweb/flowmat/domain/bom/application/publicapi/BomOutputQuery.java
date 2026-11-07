package org.myweb.flowmat.domain.bom.application.publicapi;

import java.util.Set;

/** Read-only classification of outputs from the revision already pinned to a run. */
public interface BomOutputQuery {
    /** Includes retired/deleted historical revisions; never substitutes the latest approved revision. */
    Set<String> findByProductItemIds(String projectId, String bomId);

    /** Items of the revision's waste lines, under the same rules (docs/domain/bom-by-products.md WD4). */
    Set<String> findWasteItemIds(String projectId, String bomId);
}
