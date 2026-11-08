package org.myweb.flowmat.domain.inventory.application;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.application.publicapi.LotReleaseCommand;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LotReleaseCommandImpl implements LotReleaseCommand {

    private final InventoryCommandService inventoryCommandService;

    @Override
    public String releaseAfterReceiptChecks(String lotId, String actorUserId) {
        return inventoryCommandService.releaseInspection(lotId, actorUserId);
    }
}
