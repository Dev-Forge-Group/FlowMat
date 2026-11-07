package org.myweb.flowmat.domain.inventory.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The work orders inventory picks for: one to plan a pick list from, and their numbers to show on warehouse tasks.
 * Production owns work orders and implements this, so inventory does not depend on production (production already
 * depends on inventory).
 */
public interface PickWorkOrders {

    /**
     * What picking needs to know about a work order. {@code acceptsRuns}: approved or in progress, so picks can be made
     * for it. {@code plannedStartAt}: its day picks a phantom's revision (docs/domain/multi-level-bom.md P3).
     */
    record PickWorkOrder(
        String workOrderId,
        String workOrderNumber,
        String workOrderStatus,
        boolean acceptsRuns,
        String bomId,
        String targetItemId,
        BigDecimal targetQuantity,
        OffsetDateTime plannedStartAt,
        List<String> optionalItemIds
    ) {

        public PickWorkOrder {
            optionalItemIds = optionalItemIds == null ? List.of() : List.copyOf(optionalItemIds);
        }
    }

    /** The work order if it exists, is not deleted and belongs to the project. */
    Optional<PickWorkOrder> findProjectWorkOrder(String projectId, String workOrderId);

    /** Work orders by id, deleted ones included so old references still show a number; ids that do not exist are left out. */
    Map<String, PickWorkOrder> findWorkOrders(Collection<String> workOrderIds);
}
