package org.myweb.flowmat.domain.inventory.api.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * LOT genealogy from one LOT. backward: the material LOTs it was made from (and theirs, recursively);
 * forward: the LOTs made from it — what a quarantine of this LOT affects.
 */
public record LotTraceResponse(
    LotResponse lot,
    String direction,
    List<Node> nodes
) {

    /** One reached LOT; {@code viaLotId} is the neighbour one step closer to the starting LOT. */
    public record Node(
        LotResponse lot,
        int depth,
        String viaLotId,
        String productionRunId,
        BigDecimal consumedQty,
        BigDecimal producedQty,
        String unit
    ) {
    }
}
