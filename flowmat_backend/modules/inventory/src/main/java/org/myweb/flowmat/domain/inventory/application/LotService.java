package org.myweb.flowmat.domain.inventory.application;

import java.util.List;
import org.myweb.flowmat.domain.inventory.api.dto.request.LotCreateRequest;
import org.myweb.flowmat.domain.inventory.application.publicapi.LotTraceCommand;
import org.myweb.flowmat.domain.inventory.domain.entity.LotMaster;
import org.myweb.flowmat.domain.inventory.api.dto.response.LotResponse;
import org.myweb.flowmat.domain.inventory.api.dto.response.LotTraceResponse;

/** LOTs and their genealogy (docs/domain/inventory-bom-lot-contract.md §6). Production records genealogy through {@link LotTraceCommand}. */
public interface LotService extends LotTraceCommand {

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
}
