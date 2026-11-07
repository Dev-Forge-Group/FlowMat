package org.myweb.flowmat.domain.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.inventory.api.dto.response.StockSnapshotResponse;
import org.myweb.flowmat.domain.inventory.domain.entity.Inventory;
import org.myweb.flowmat.domain.inventory.domain.entity.InventoryTransaction;
import org.myweb.flowmat.domain.inventory.domain.entity.LotMaster;
import org.myweb.flowmat.domain.inventory.repository.InventoryRepository;
import org.myweb.flowmat.domain.inventory.repository.InventoryTransactionRepository;
import org.myweb.flowmat.domain.inventory.repository.InventoryTransactionRepository.LastMovement;
import org.myweb.flowmat.domain.inventory.repository.LotMasterRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock at a past moment (docs/domain/stock-ledger.md "과거 시점 재고"). Every movement records the quantity it left
 * behind, so a record's stock at a moment is what its last movement up to then left. A record that has never moved
 * still holds what it was created with, from its creation on. Records deleted since are included if they held stock
 * then. Read only.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockSnapshotService {

    private static final int SCALE = 4;

    private final InventoryTransactionRepository transactionRepository;
    private final InventoryRepository inventoryRepository;
    private final LotMasterRepository lotMasterRepository;
    private final CatalogQuery catalogQuery;
    private final ProjectAccessService projectAccessService;

    public StockSnapshotResponse at(String projectId, OffsetDateTime at) {
        projectAccessService.requireProjectReadAccess(projectId);
        if (at == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Give the moment to show stock at.");
        }

        // The last movement of each record up to the moment, one per record from the database.
        Map<String, LastMovement> last = transactionRepository.findLastMovementsUpTo(projectId, at).stream()
            .collect(Collectors.toMap(LastMovement::getInventoryId, Function.identity()));
        Set<String> moved = transactionRepository.findInventoryIdsWithMovements(projectId);
        Map<String, Inventory> records = StreamSupport.stream(inventoryRepository.findAllById(last.keySet()).spliterator(), false)
            .collect(Collectors.toMap(Inventory::getInventoryId, Function.identity()));
        inventoryRepository.findAllByProjectIdAndDeletedYnOrderByCreatedAtAsc(projectId, "N").stream()
            .filter(row -> !moved.contains(row.getInventoryId()))
            .filter(row -> row.getCreatedAt() != null && !row.getCreatedAt().isAfter(at))
            .forEach(row -> records.put(row.getInventoryId(), row));

        Map<String, CatalogItemView> items = catalogQuery.findItems(
            records.values().stream().map(Inventory::getItemId).collect(Collectors.toSet()));
        Map<String, LotMaster> lots = StreamSupport.stream(lotMasterRepository.findAllById(
                records.values().stream().map(Inventory::getLotId).filter(Objects::nonNull).collect(Collectors.toSet())).spliterator(), false)
            .collect(Collectors.toMap(LotMaster::getLotId, Function.identity()));

        List<StockSnapshotResponse.Row> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        boolean complete = true;
        for (Inventory record : records.values()) {
            LastMovement movement = last.get(record.getInventoryId());
            boolean fromLedger = movement != null;
            BigDecimal quantity = scale(fromLedger ? movement.getQuantityAfter() : record.getQuantity());
            BigDecimal reserved = scale(fromLedger ? movement.getReservedAfter() : record.getReservedQuantity());
            if (quantity.signum() == 0 && reserved.signum() == 0) {
                continue;
            }
            CatalogItemView item = items.get(record.getItemId());
            BigDecimal unitCost = item != null && item.unitCost() != null && item.unitCost().signum() > 0 ? item.unitCost() : null;
            BigDecimal value = unitCost == null ? null : scale(quantity.multiply(unitCost));
            if (value == null) {
                complete = false;
            } else {
                total = total.add(value);
            }
            LotMaster lot = record.getLotId() == null ? null : lots.get(record.getLotId());
            rows.add(new StockSnapshotResponse.Row(
                record.getInventoryId(),
                record.getItemId(),
                item == null ? null : item.itemCode(),
                item == null ? null : item.itemName(),
                item == null ? null : item.unitCode(),
                record.getLocation(),
                record.getLotId(),
                lot == null ? null : lot.getLotNo(),
                quantity,
                reserved,
                value,
                fromLedger
            ));
        }
        rows.sort(Comparator.comparing(StockSnapshotResponse.Row::itemCode, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(StockSnapshotResponse.Row::location, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(StockSnapshotResponse.Row::lotNo, Comparator.nullsLast(Comparator.naturalOrder())));
        return new StockSnapshotResponse(at, scale(total), complete, rows);
    }

    private static BigDecimal scale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(SCALE, RoundingMode.HALF_UP);
    }
}
