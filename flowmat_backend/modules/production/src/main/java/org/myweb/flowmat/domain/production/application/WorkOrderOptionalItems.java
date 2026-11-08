package org.myweb.flowmat.domain.production.application;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrderOptionalItem;
import org.myweb.flowmat.domain.production.repository.WorkOrderOptionalItemRepository;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Component;

/**
 * The optional BOM materials each work order picked, by item (ADR-033 decisions 2-4): an optional line is planned only
 * for an order that picked its item. Reads and replaces the rows; {@link WorkOrderOptionalItemCheck} checks them.
 */
@Component
@RequiredArgsConstructor
public class WorkOrderOptionalItems {

    private final WorkOrderOptionalItemRepository repository;
    private final IdGenerator idGenerator;

    /** The order's picks by item id, in id order; empty when it picked none. */
    public List<String> of(String workOrderId) {
        return of(List.of(workOrderId)).getOrDefault(workOrderId, List.of());
    }

    /** Order id → its picks, for orders that picked any. */
    public Map<String, List<String>> of(Collection<String> workOrderIds) {
        Map<String, List<String>> picks = new HashMap<>();
        if (workOrderIds.isEmpty()) {
            return picks;
        }
        repository.findAllByWorkOrderIdInOrderByItemIdAsc(workOrderIds)
            .forEach(row -> picks.computeIfAbsent(row.getWorkOrderId(), id -> new ArrayList<>()).add(row.getItemId()));
        return picks;
    }

    /** Puts {@code itemIds} in place of the order's picks; the caller has checked them. */
    void replace(String workOrderId, Collection<String> itemIds, String actor) {
        repository.deleteAllByWorkOrderId(workOrderId);
        // Deletes go out before the new rows, which may name the same items.
        repository.flush();
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        for (String itemId : itemIds) {
            WorkOrderOptionalItem row = new WorkOrderOptionalItem();
            row.setWorkOrderOptionalItemId(idGenerator.generate());
            row.setWorkOrderId(workOrderId);
            row.setItemId(itemId);
            row.setCreatedBy(actor);
            row.setCreatedAt(now);
            repository.save(row);
        }
    }
}
