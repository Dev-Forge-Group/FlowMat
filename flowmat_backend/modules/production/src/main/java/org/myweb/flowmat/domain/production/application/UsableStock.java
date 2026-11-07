package org.myweb.flowmat.domain.production.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockView;
import org.myweb.flowmat.domain.inventory.application.publicapi.LotView;
import org.springframework.stereotype.Component;

/**
 * Stock production can draw on right now, per item in the item's own unit: available quantity (on hand − reserved) of
 * active stock records, leaving out quarantined records and records in closed or expired LOTs. The reorder list and
 * work order readiness count the same way (docs/domain/material-requirements.md M4).
 */
@Component
@RequiredArgsConstructor
public class UsableStock {

    private static final String NOT_DELETED = "N";
    private final ProductionPlanningReferences references;
    private final org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery projectCalendar;



    public Map<String, BigDecimal> byItem(String projectId, Collection<String> itemIds) {
        List<StockView> rows = references.projectStocks(projectId).stream()
            .filter(row -> itemIds.contains(row.itemId()))
            .toList();
        Map<String, LotView> lots = StreamSupport.stream(references.lots(
                rows.stream().map(StockView::lotId).filter(Objects::nonNull).collect(Collectors.toSet())).spliterator(), false)
            .collect(Collectors.toMap(LotView::lotId, Function.identity()));
        LocalDate today = projectCalendar.today(projectId);
        Map<String, BigDecimal> usable = new HashMap<>();
        for (StockView row : rows) {
            LotView lot = row.lotId() == null ? null : lots.get(row.lotId());
            if ("quarantined".equals(row.inventoryStatus())
                || lot != null && ("closed".equals(lot.lotStatus()) || lot.isExpiredOn(today))) {
                continue;
            }
            usable.merge(row.itemId(), row.availableQuantity() == null ? BigDecimal.ZERO : row.availableQuantity(), BigDecimal::add);
        }
        return usable;
    }
}
