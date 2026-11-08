package org.myweb.flowmat.domain.inventory.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.inventory.api.dto.response.StockWasteResponse;
import org.myweb.flowmat.domain.inventory.repository.InventoryTransactionRepository;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;

/**
 * Adds up stock lost in the last days by why (docs/domain/stock-analysis.md "폐기·손실"), from the movements that took
 * stock away: expired stock written off ({@code expiry_write_off}), stock scrapped when a defect was resolved
 * ({@code defect_log}) and stock counts that found less than the records said ({@code inventory_count}). Gains from counts
 * do not offset losses. A movement that was reversed is left out.
 */
@Service
@RequiredArgsConstructor
public class StockWasteService {

    private static final int MAX_DAYS = 365;

    private final ProjectAccess projectAccessService;
    private final InventoryTransactionRepository inventoryTransactionRepository;
    private final CatalogQuery catalogQuery;

    private enum Reason { EXPIRED, DEFECT, COUNT_LOSS }

    public StockWasteResponse waste(String projectId, Integer days) {
        String id = projectId == null ? "" : projectId.trim();
        if (id.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "projectId is required.");
        }
        int span = days == null ? 30 : days;
        if (span < 1 || span > MAX_DAYS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "days must be between 1 and " + MAX_DAYS + ".");
        }
        projectAccessService.requireProjectReadAccess(id);
        OffsetDateTime to = OffsetDateTime.now();
        OffsetDateTime from = to.minusDays(span);
        // Added up in the database, which also leaves out movements reversed after the period.
        Map<String, Map<Reason, BigDecimal>> byItem = new HashMap<>();
        for (InventoryTransactionRepository.ItemWaste totals : inventoryTransactionRepository.findItemWaste(id, from)) {
            Map<Reason, BigDecimal> lost = new HashMap<>();
            lost.put(Reason.EXPIRED, totals.getExpired());
            lost.put(Reason.DEFECT, totals.getDefect());
            lost.put(Reason.COUNT_LOSS, totals.getCountLoss());
            byItem.put(totals.getItemId(), lost);
        }

        Map<String, CatalogItemView> items = catalogQuery.findItems(byItem.keySet());
        List<StockWasteResponse.Line> lines = new ArrayList<>();
        BigDecimal[] reasonValues = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        boolean complete = true;
        for (Map.Entry<String, Map<Reason, BigDecimal>> entry : byItem.entrySet()) {
            CatalogItemView item = items.get(entry.getKey());
            Map<Reason, BigDecimal> lost = entry.getValue();
            BigDecimal expired = lost.getOrDefault(Reason.EXPIRED, BigDecimal.ZERO);
            BigDecimal defect = lost.getOrDefault(Reason.DEFECT, BigDecimal.ZERO);
            BigDecimal countLoss = lost.getOrDefault(Reason.COUNT_LOSS, BigDecimal.ZERO);
            BigDecimal total = expired.add(defect).add(countLoss);
            BigDecimal cost = item == null || item.unitCost() == null || item.unitCost().signum() <= 0 ? null : item.unitCost();
            if (cost == null) {
                complete = false;
            } else {
                reasonValues[0] = reasonValues[0].add(expired.multiply(cost));
                reasonValues[1] = reasonValues[1].add(defect.multiply(cost));
                reasonValues[2] = reasonValues[2].add(countLoss.multiply(cost));
            }
            String unit = item == null ? null : item.unitCode();
            lines.add(new StockWasteResponse.Line(entry.getKey(), item == null ? null : item.itemCode(),
                item == null ? null : item.itemName(), unit, expired, defect, countLoss, total,
                cost == null ? null : total.multiply(cost)));
        }
        lines.sort(Comparator.comparing(StockWasteResponse.Line::value, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(StockWasteResponse.Line::total, Comparator.reverseOrder()));
        BigDecimal value = reasonValues[0].add(reasonValues[1]).add(reasonValues[2]);
        return new StockWasteResponse(span, from, to, value, complete, reasonValues[0], reasonValues[1], reasonValues[2], lines);
    }
}
