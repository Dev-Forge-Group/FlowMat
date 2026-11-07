package org.myweb.flowmat.domain.catalog.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogUnitCostQuery;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogUnitCostView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogUnitCostView.CostBasis;
import org.myweb.flowmat.domain.catalog.domain.entity.Item;
import org.myweb.flowmat.domain.catalog.domain.entity.ItemCostHistory;
import org.myweb.flowmat.domain.catalog.repository.ItemCostHistoryRepository;
import org.myweb.flowmat.domain.catalog.repository.ItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** D+ price basis, without exposing catalog entities or issuing one history query per material. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CatalogUnitCostQueryImpl implements CatalogUnitCostQuery {
    private final ItemRepository items;
    private final ItemCostHistoryRepository history;

    @Override
    public Map<String, CatalogUnitCostView> findUnitCostsAt(String projectId, Collection<String> itemIds, OffsetDateTime basisAt) {
        Objects.requireNonNull(projectId, "projectId");
        if (itemIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Item> ownItems = new LinkedHashMap<>();
        items.findAllById(itemIds).stream().filter(item -> projectId.equals(item.getProjectId()))
            .forEach(item -> ownItems.put(item.getItemId(), item));
        if (ownItems.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, List<ItemCostHistory>> byItem = new LinkedHashMap<>();
        if (basisAt != null) {
            for (ItemCostHistory row : history.findAllByProjectIdAndItemIdInOrderByChangedAtDescItemCostHistoryIdDesc(projectId, ownItems.keySet())) {
                byItem.computeIfAbsent(row.getItemId(), ignored -> new ArrayList<>()).add(row);
            }
        }
        Map<String, CatalogUnitCostView> result = new LinkedHashMap<>();
        ownItems.forEach((id, item) -> result.put(id, price(item, byItem.getOrDefault(id, List.of()), basisAt)));
        return Collections.unmodifiableMap(result);
    }

    private static CatalogUnitCostView price(Item item, List<ItemCostHistory> rows, OffsetDateTime at) {
        if (at == null) {
            return view(item, item.getUnitCost(), null, CostBasis.CURRENT);
        }
        if (rows.isEmpty()) {
            return view(item, item.getUnitCost(), at, CostBasis.ESTIMATED);
        }
        ItemCostHistory selected = rows.stream().filter(row -> !row.getChangedAt().isAfter(at)).findFirst().orElse(null);
        if (selected != null) {
            // A DB timestamp can lose sub-microsecond order; never present conflicting tied changes as exact.
            boolean ambiguous = rows.stream().anyMatch(row -> row.getChangedAt().isEqual(selected.getChangedAt())
                && !sameCost(row.getUnitCost(), selected.getUnitCost()));
            return ambiguous ? view(item, item.getUnitCost(), at, CostBasis.ESTIMATED)
                : view(item, selected.getUnitCost(), at, CostBasis.HISTORICAL);
        }
        ItemCostHistory earliest = rows.getLast();
        boolean ambiguous = rows.stream().anyMatch(row -> row.getChangedAt().isEqual(earliest.getChangedAt())
            && !sameCost(row.getPreviousUnitCost(), earliest.getPreviousUnitCost()));
        return view(item, ambiguous ? item.getUnitCost() : earliest.getPreviousUnitCost(), at, CostBasis.ESTIMATED);
    }

    private static CatalogUnitCostView view(Item item, BigDecimal cost, OffsetDateTime at, CostBasis basis) {
        return new CatalogUnitCostView(item.getItemId(), known(cost), at, basis);
    }

    private static BigDecimal known(BigDecimal cost) {
        return cost == null || cost.signum() <= 0 ? null : cost;
    }

    private static boolean sameCost(BigDecimal first, BigDecimal second) {
        BigDecimal a = known(first);
        BigDecimal b = known(second);
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }
}
