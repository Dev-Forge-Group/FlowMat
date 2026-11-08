package org.myweb.flowmat.domain.inventory.application.publicapi;

import java.math.BigDecimal;

/**
 * LOT genealogy a production run records (docs/domain/inventory-bom-lot-contract.md §6,
 * docs/architecture/adr/ADR-036-cross-context-contract-types.md decision 6). Access checks are the caller's job.
 */
public interface LotTraceCommand {

    /** Records that {@code parentLotId} went into {@code childLotId} in a run; recording the same edge twice is a no-op. */
    void recordTrace(
        String projectId,
        String productionRunId,
        String processId,
        String parentLotId,
        String childLotId,
        BigDecimal consumedQty,
        BigDecimal producedQty,
        String unit
    );

    /** Marks a LOT as produced by a run if nothing else claimed it yet. */
    void markProducedBy(String lotId, String productionRunId);

    /** Undoes {@link #markProducedBy} when the run no longer produces that LOT (its output was cancelled). */
    void clearProducedBy(String lotId, String productionRunId);

    /** Drops every genealogy edge of a run so it can be rebuilt from the run's remaining items. */
    void clearRunTrace(String productionRunId);
}
