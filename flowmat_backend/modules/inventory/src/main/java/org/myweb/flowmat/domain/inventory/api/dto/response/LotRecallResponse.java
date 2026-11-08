package org.myweb.flowmat.domain.inventory.api.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * Everything a suspect LOT went into (docs/domain/lot-recall.md): the LOT itself and every LOT made from it, with the
 * stock each still holds and what already left through issues.
 *
 * @param lots the starting LOT first (depth 0), then the forward genealogy
 */
public record LotRecallResponse(String lotId, String lotNo, List<Line> lots) {

    /**
     * @param viaLotNo the LOT one step closer to the start that this one was made from; null for the start
     * @param onHand on hand over all its records, in the item's unit
     * @param places "WH-A 4" for each record holding stock
     * @param issued what issues took out, less those reversed
     */
    public record Line(
        String lotId,
        String lotNo,
        String itemId,
        String itemCode,
        String itemName,
        String unit,
        int depth,
        String viaLotNo,
        String lotStatus,
        BigDecimal onHand,
        List<String> places,
        BigDecimal issued
    ) {
    }
}
