package org.myweb.flowmat.domain.inventory.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceUnitUtil;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockView;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockViewQuery;
import org.myweb.flowmat.domain.inventory.domain.entity.Inventory;
import org.myweb.flowmat.domain.inventory.repository.InventoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockViewQueryImpl implements StockViewQuery {

    private static final String NOT_DELETED = "N";

    private final InventoryRepository inventoryRepository;
    private final EntityManager entityManager;

    @Override
    public Optional<StockView> findActiveStock(String inventoryId) {
        return inventoryRepository.findByInventoryIdAndDeletedYn(inventoryId, NOT_DELETED).map(StockViewQueryImpl::view);
    }

    @Override
    public List<StockView> findStocks(Collection<String> inventoryIds) {
        return inventoryRepository.findAllById(inventoryIds).stream().map(StockViewQueryImpl::view).toList();
    }

    @Override
    public List<StockView> projectStocks(String projectId) {
        return inventoryRepository.findAllByProjectIdAndDeletedYnOrderByCreatedAtAsc(projectId, NOT_DELETED).stream()
            .map(StockViewQueryImpl::view)
            .toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<StockView> lockItemStock(String projectId, String itemId) {
        List<String> ids = inventoryRepository.lockItemStock(projectId, itemId);
        Set<String> loaded = new HashSet<>();
        PersistenceUnitUtil persistence = entityManager.getEntityManagerFactory().getPersistenceUnitUtil();
        // The native row lock does not refresh an entity already read by the caller's persistence context.
        // getReference/isLoaded inspect that context without selecting each previously unseen row.
        for (String id : ids) {
            if (persistence.isLoaded(entityManager.getReference(Inventory.class, id))) {
                loaded.add(id);
            }
        }
        return inventoryRepository.findAllById(ids).stream().map(row -> {
            if (loaded.contains(row.getInventoryId())) {
                entityManager.refresh(row);
            }
            return view(row);
        }).toList();
    }

    private static StockView view(Inventory row) {
        return new StockView(row.getInventoryId(), row.getProjectId(), row.getItemId(), row.getLotId(), row.getLocation(),
            row.getQuantity(), row.getReservedQuantity(), row.getAvailableQuantity(), row.getInventoryStatus(), row.getCreatedAt());
    }
}
