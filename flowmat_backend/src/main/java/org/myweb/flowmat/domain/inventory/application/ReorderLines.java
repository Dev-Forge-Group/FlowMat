package org.myweb.flowmat.domain.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.inventory.api.dto.response.ReorderLineResponse;
import org.myweb.flowmat.domain.inventory.domain.entity.Inventory;
import org.myweb.flowmat.domain.inventory.domain.entity.LotMaster;

/**
 * The reorder list's arithmetic, shared by the item-level list and the list of one place (docs/domain/stock-alert.md
 * "재주문 목록" and PS4): usable stock per item, and the items short of their safety stock.
 */
final class ReorderLines {

    private ReorderLines() {
    }

    /**
     * Usable stock per item among the rows taken: what could go into production, so not quarantined and not of a closed or
     * expired LOT, as the work order readiness check counts it.
     */
    static Map<String, BigDecimal> usable(List<Inventory> rows, Map<String, LotMaster> lots, LocalDate today, Predicate<Inventory> taken) {
        Map<String, BigDecimal> usable = new HashMap<>();
        for (Inventory row : rows) {
            if (!taken.test(row) || "quarantined".equals(row.getInventoryStatus())) {
                continue;
            }
            LotMaster lot = row.getLotId() == null ? null : lots.get(row.getLotId());
            if (lot != null && ("closed".equals(lot.getLotStatus()) || lot.isExpiredOn(today))) {
                continue;
            }
            usable.merge(row.getItemId(), row.getAvailableQuantity() == null ? BigDecimal.ZERO : row.getAvailableQuantity(), BigDecimal::add);
        }
        return usable;
    }

    /** The watched items short of their safety stock, the biggest share short first, then by code. */
    static List<ReorderLineResponse> shortLines(List<CatalogItemView> watched, Map<String, BigDecimal> safetyStock, Map<String, BigDecimal> usable,
                                                Map<String, String> units, String location) {
        return watched.stream()
            .map(item -> {
                BigDecimal safety = safetyStock.get(item.itemId());
                BigDecimal available = usable.getOrDefault(item.itemId(), BigDecimal.ZERO);
                return new ReorderLineResponse(item.itemId(), item.itemCode(), item.itemName(), units.get(item.unitId()),
                    safety, available, safety.subtract(available), item.leadTimeDays(), location);
            })
            .filter(line -> line.shortageQuantity().signum() > 0)
            .sorted(Comparator.comparing(
                    (ReorderLineResponse line) -> line.shortageQuantity().divide(line.safetyStockQty(), 6, RoundingMode.HALF_UP))
                .reversed()
                .thenComparing(ReorderLineResponse::itemCode))
            .toList();
    }
}
