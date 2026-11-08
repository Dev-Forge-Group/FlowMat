package org.myweb.flowmat.domain.inventory.application;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockQuery;
import org.myweb.flowmat.domain.inventory.domain.entity.InventoryTransaction;
import org.myweb.flowmat.domain.inventory.repository.InventoryRepository;
import org.myweb.flowmat.domain.inventory.repository.InventoryTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockQueryImpl implements StockQuery {

    private static final String NOT_DELETED = "N";

    private final InventoryRepository inventoryRepository;
    private final InventoryTransactionRepository inventoryTransactionRepository;

    @Override
    public BigDecimal quantityMovedBy(String referenceType, String referenceId) {
        return inventoryTransactionRepository.findAllByReferenceTypeAndReferenceId(referenceType, referenceId).stream()
            .map(InventoryTransaction::getQuantityDelta)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    public Optional<BigDecimal> availableQuantity(String inventoryId) {
        return inventoryRepository.findByInventoryIdAndDeletedYn(inventoryId, NOT_DELETED)
            .map(stock -> zeroIfNull(stock.getQuantity()).subtract(zeroIfNull(stock.getReservedQuantity())));
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
