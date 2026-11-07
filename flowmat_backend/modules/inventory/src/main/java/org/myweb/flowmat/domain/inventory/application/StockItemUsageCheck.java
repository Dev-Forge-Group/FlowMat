package org.myweb.flowmat.domain.inventory.application;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.ItemUsageCheck;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.inventory.repository.InventoryRepository;
import org.springframework.stereotype.Component;

/**
 * An item cannot be deleted while it has stock records, even empty ones: the records (and their history) would point at
 * an item nobody can see any more. Empty records can be deleted first.
 */
@Component
@RequiredArgsConstructor
public class StockItemUsageCheck implements ItemUsageCheck {

    private static final String NOT_DELETED = "N";

    private final InventoryRepository inventoryRepository;

    @Override
    public Optional<String> whyInUse(CatalogItemView item) {
        return inventoryRepository.existsByItemIdAndDeletedYn(item.itemId(), NOT_DELETED)
            ? Optional.of("it still has stock records; issue the stock and delete the empty records first")
            : Optional.empty();
    }
}
