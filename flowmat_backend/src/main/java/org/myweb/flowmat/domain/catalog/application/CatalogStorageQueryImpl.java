package org.myweb.flowmat.domain.catalog.application;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogStorageQuery;
import org.myweb.flowmat.domain.catalog.domain.entity.Item;
import org.myweb.flowmat.domain.catalog.repository.ItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Items' storage conditions for inventory (docs/domain/storage-location.md SL5). */
@Service
@RequiredArgsConstructor
public class CatalogStorageQueryImpl implements CatalogStorageQuery {

    private final ItemRepository itemRepository;

    @Override
    @Transactional(readOnly = true)
    public Map<String, ItemStorage> findStorage(Collection<String> itemIds) {
        return itemRepository.findAllById(itemIds).stream()
            .collect(Collectors.toMap(Item::getItemId, item -> new ItemStorage(item.getItemCode(), item.getStorageCondition())));
    }
}
