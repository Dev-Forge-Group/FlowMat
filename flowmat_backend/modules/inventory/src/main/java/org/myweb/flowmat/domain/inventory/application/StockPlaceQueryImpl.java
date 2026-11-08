package org.myweb.flowmat.domain.inventory.application;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockPlaceQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Places checked as new stock is, for purchasing (docs/domain/purchasing.md PO39). */
@Service
@RequiredArgsConstructor
public class StockPlaceQueryImpl implements StockPlaceQuery {

    private final StorageLocationService storageLocationService;

    @Override
    @Transactional(readOnly = true)
    public String placeFor(String projectId, String location) {
        return storageLocationService.locationCheck(projectId).resolve(location);
    }
}
