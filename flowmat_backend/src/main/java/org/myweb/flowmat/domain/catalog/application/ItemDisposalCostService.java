package org.myweb.flowmat.domain.catalog.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.api.dto.request.ItemDisposalCostRequest;
import org.myweb.flowmat.domain.catalog.api.dto.response.ItemDisposalCostChangeResponse;
import org.myweb.flowmat.domain.catalog.api.dto.response.ItemDisposalCostResponse;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogDisposalCostQuery;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogUnitCostView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogUnitCostView.CostBasis;
import org.myweb.flowmat.domain.catalog.domain.entity.Item;
import org.myweb.flowmat.domain.catalog.domain.entity.ItemDisposalCost;
import org.myweb.flowmat.domain.catalog.domain.entity.ItemDisposalCostHistory;
import org.myweb.flowmat.domain.catalog.repository.ItemDisposalCostHistoryRepository;
import org.myweb.flowmat.domain.catalog.repository.ItemDisposalCostRepository;
import org.myweb.flowmat.domain.catalog.repository.ItemRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * An item's disposal cost and its history (docs/domain/bom-by-products.md WD1-WD3). It is not Item.unitCost: a waste's
 * value and what it costs to get rid of it are different numbers (DECISIONS-2026-10-05 section 4).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ItemDisposalCostService implements CatalogDisposalCostQuery {
    private final ItemRepository items;
    private final ItemDisposalCostRepository costs;
    private final ItemDisposalCostHistoryRepository history;
    private final ProjectAccessService access;
    private final IdGenerator ids;
    private final EntityManager entities;

    public ItemDisposalCostResponse get(String itemId) {
        Item item = live(itemId);
        access.requireProjectReadAccess(item.getProjectId());
        return costs.findById(itemId).map(ItemDisposalCostService::response)
            .orElse(new ItemDisposalCostResponse(itemId, null, 0, null, null));
    }

    /** The latest 50 changes, newest first (WD7). */
    public List<ItemDisposalCostChangeResponse> history(String itemId) {
        Item item = live(itemId);
        access.requireProjectReadAccess(item.getProjectId());
        return history.findTop50ByItemIdOrderByChangedAtDescItemDisposalCostHistoryIdDesc(itemId).stream()
            .map(change -> new ItemDisposalCostChangeResponse(change.getPreviousDisposalCost(), change.getDisposalCost(),
                change.getChangedBy(), change.getChangedAt()))
            .toList();
    }

    /** Saves under the item's row lock with the loaded version; the author's exact retry after a lost reply is answered (WD2). */
    @Transactional
    public ItemDisposalCostResponse set(String itemId, ItemDisposalCostRequest request) {
        Item item = live(itemId);
        access.requireProjectWriteAccess(item.getProjectId());
        entities.refresh(item, LockModeType.PESSIMISTIC_WRITE);
        if (!"N".equals(item.getDeletedYn())) throw new BusinessException(ErrorCode.NOT_FOUND);
        BigDecimal cost = request.disposalCost();
        if (cost != null) {
            if (cost.signum() == 0) cost = BigDecimal.ZERO;
            if (cost.signum() < 0 || (long) cost.precision() - cost.scale() > 10 || cost.stripTrailingZeros().scale() > 4)
                throw new BusinessException(ErrorCode.BAD_REQUEST, "disposalCost must be nonnegative, with at most 10 whole digits and 4 decimals.");
            cost = cost.setScale(4);
        }
        if (request.expectedVersion() < 0)
            throw new BusinessException(ErrorCode.BAD_REQUEST, "expectedVersion must be a nonnegative integer.");
        String actor = access.requireCurrentUserId();
        ItemDisposalCost current = costs.findById(itemId).orElse(null);
        long version = current == null ? 0 : current.getVersion();
        if (version != request.expectedVersion()) {
            if (current != null && version > 0 && request.expectedVersion() == version - 1
                && actor.equals(current.getUpdatedBy()) && same(cost, current.getDisposalCost())) return response(current);
            throw new BusinessException(ErrorCode.CONFLICT, "expectedVersion changed; reload the current disposalCost before saving a different one.");
        }
        if (version == Long.MAX_VALUE) throw new BusinessException(ErrorCode.CONFLICT, "expectedVersion has reached its limit.");
        BigDecimal previous = current == null ? null : current.getDisposalCost();
        if (current == null) {
            current = new ItemDisposalCost();
            current.setItemId(itemId);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        current.setDisposalCost(cost);
        current.setVersion(version + 1);
        current.setUpdatedBy(actor);
        current.setUpdatedAt(now);
        ItemDisposalCost saved = costs.save(current);
        // Only a change of the cost is history; saving the same cost again is not.
        if (!same(previous, cost)) {
            ItemDisposalCostHistory change = new ItemDisposalCostHistory();
            change.setItemDisposalCostHistoryId(ids.generate());
            change.setProjectId(item.getProjectId());
            change.setItemId(itemId);
            change.setPreviousDisposalCost(previous);
            change.setDisposalCost(cost);
            change.setChangedBy(actor);
            change.setChangedAt(now);
            history.save(change);
        }
        return response(saved);
    }

    @Override
    public Map<String, CatalogUnitCostView> findDisposalCostsAt(String projectId, Collection<String> itemIds, OffsetDateTime basisAt) {
        Objects.requireNonNull(projectId, "projectId");
        if (itemIds == null || itemIds.isEmpty()) return Collections.emptyMap();
        List<String> own = items.findAllById(itemIds).stream().filter(item -> projectId.equals(item.getProjectId()))
            .map(Item::getItemId).toList();
        if (own.isEmpty()) return Collections.emptyMap();
        Map<String, BigDecimal> current = new LinkedHashMap<>();
        costs.findAllById(own).forEach(one -> current.put(one.getItemId(), one.getDisposalCost()));
        Map<String, List<ItemDisposalCostHistory>> byItem = new LinkedHashMap<>();
        if (basisAt != null) {
            for (ItemDisposalCostHistory row
                : history.findAllByProjectIdAndItemIdInOrderByChangedAtDescItemDisposalCostHistoryIdDesc(projectId, own)) {
                byItem.computeIfAbsent(row.getItemId(), ignored -> new ArrayList<>()).add(row);
            }
        }
        Map<String, CatalogUnitCostView> result = new LinkedHashMap<>();
        for (String id : own) {
            result.put(id, cost(id, current.get(id), byItem.getOrDefault(id, List.of()), basisAt));
        }
        return Collections.unmodifiableMap(result);
    }

    /** The same D+ rules as unit prices (docs/domain/material-cost.md), except that zero is a known cost. */
    private static CatalogUnitCostView cost(String itemId, BigDecimal now, List<ItemDisposalCostHistory> rows, OffsetDateTime at) {
        if (at == null) return new CatalogUnitCostView(itemId, now, null, CostBasis.CURRENT);
        if (rows.isEmpty()) return new CatalogUnitCostView(itemId, now, at, CostBasis.ESTIMATED);
        ItemDisposalCostHistory selected = rows.stream().filter(row -> !row.getChangedAt().isAfter(at)).findFirst().orElse(null);
        if (selected != null) {
            // A DB timestamp can lose sub-microsecond order; never present conflicting tied changes as exact.
            boolean ambiguous = rows.stream().anyMatch(row -> row.getChangedAt().isEqual(selected.getChangedAt())
                && !same(row.getDisposalCost(), selected.getDisposalCost()));
            return ambiguous ? new CatalogUnitCostView(itemId, now, at, CostBasis.ESTIMATED)
                : new CatalogUnitCostView(itemId, selected.getDisposalCost(), at, CostBasis.HISTORICAL);
        }
        ItemDisposalCostHistory earliest = rows.getLast();
        boolean ambiguous = rows.stream().anyMatch(row -> row.getChangedAt().isEqual(earliest.getChangedAt())
            && !same(row.getPreviousDisposalCost(), earliest.getPreviousDisposalCost()));
        return new CatalogUnitCostView(itemId, ambiguous ? now : earliest.getPreviousDisposalCost(), at, CostBasis.ESTIMATED);
    }

    private Item live(String itemId) {
        return items.findByItemIdAndDeletedYn(itemId, "N").orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private static boolean same(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static ItemDisposalCostResponse response(ItemDisposalCost cost) {
        return new ItemDisposalCostResponse(cost.getItemId(), cost.getDisposalCost(), cost.getVersion(), cost.getUpdatedBy(),
            cost.getUpdatedAt());
    }
}
