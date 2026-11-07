package org.myweb.flowmat.domain.inventory.application;

import java.math.BigDecimal;
import java.util.List;
import org.myweb.flowmat.domain.inventory.api.dto.request.LotCreateRequest;
import org.myweb.flowmat.domain.inventory.domain.entity.LotMaster;
import org.myweb.flowmat.domain.inventory.api.dto.response.LotResponse;
import org.myweb.flowmat.domain.inventory.api.dto.response.LotTraceResponse;

/** LOTs and their genealogy (docs/domain/inventory-bom-lot-contract.md §6). */
public interface LotService {

    LotResponse createLot(LotCreateRequest request);

    List<LotResponse> listLots(String projectId, String itemId);

    LotResponse getLot(String lotId);

    /** Ends a LOT for good; only when it holds no stock. */
    LotResponse closeLot(String lotId);

    /** Opens a closed LOT again; project owners only (docs/domain/lot-release.md R6). */
    LotResponse reopenLot(String lotId);

    /** @param direction {@code backward} (towards materials) or {@code forward} (towards products) */
    LotTraceResponse trace(String lotId, String direction);

    /**
     * The LOT a new stock record may hold: same project, same item, and not closed or quarantined. Throws 400
     * otherwise. Access checks are the caller's job.
     */
    LotMaster requireLotForStock(String lotId, String projectId, String itemId);

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
