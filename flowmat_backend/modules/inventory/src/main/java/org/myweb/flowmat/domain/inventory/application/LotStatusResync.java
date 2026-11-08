package org.myweb.flowmat.domain.inventory.application;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.domain.entity.LotMaster;
import org.myweb.flowmat.domain.inventory.repository.InventoryRepository;
import org.myweb.flowmat.domain.inventory.repository.LotMasterRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-checks a LOT's status once the movement that changed one of its stock records has committed
 * (docs/domain/inventory-bom-lot-contract.md §6 "LOT 상태"). A movement works out the status inside its own transaction,
 * holding only its own record's lock, so two movements on different records of the same LOT each see the other's old
 * quantity; the LOT could stay {@code available} with nothing left. Run after commit, in a new transaction that locks only
 * the LOT row, the last re-check sees every committed change. It holds no stock record, so it cannot deadlock with a
 * movement that locks records first.
 */
@Component
@RequiredArgsConstructor
public class LotStatusResync {

    private static final String NOT_DELETED = "N";

    private final LotMasterRepository lotMasterRepository;
    private final InventoryRepository inventoryRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resync(String lotId) {
        LotMaster lot = lotMasterRepository.findForUpdate(lotId).orElse(null);
        if (lot == null || InventoryCommandService.QUARANTINED.equals(lot.getLotStatus()) || "closed".equals(lot.getLotStatus())
            || InventoryCommandService.INSPECTION_PENDING.equals(lot.getLotStatus())) {
            return;
        }
        String status = InventoryCommandService.lotStatusFor(inventoryRepository.findAllByLotIdAndDeletedYn(lotId, NOT_DELETED));
        if (!status.equals(lot.getLotStatus())) {
            lot.setLotStatus(status);
            lotMasterRepository.save(lot);
        }
    }
}
