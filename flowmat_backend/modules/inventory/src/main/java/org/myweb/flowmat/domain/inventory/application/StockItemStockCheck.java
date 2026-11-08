package org.myweb.flowmat.domain.inventory.application;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.ItemStockCheck;
import org.myweb.flowmat.domain.inventory.repository.InventoryRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Answers the catalog's {@link ItemStockCheck} from inventory's own stock records. */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockItemStockCheck implements ItemStockCheck {

    private static final String NOT_DELETED = "N";

    private final InventoryRepository inventoryRepository;

    @Override
    public boolean hasStockRecords(String itemId) {
        return inventoryRepository.existsByItemIdAndDeletedYn(itemId, NOT_DELETED);
    }
}
