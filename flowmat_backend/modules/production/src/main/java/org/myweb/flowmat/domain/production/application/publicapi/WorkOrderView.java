package org.myweb.flowmat.domain.production.application.publicapi;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * What another bounded context may read about a work order. {@code acceptsRuns}: approved or in progress, so runs,
 * allocations and picks can be made for it. {@code plannedStartAt}: its day picks a phantom's revision (docs/domain/multi-level-bom.md P3).
 */
public record WorkOrderView(
    String workOrderId,
    String projectId,
    String workOrderNumber,
    String workOrderStatus,
    boolean acceptsRuns,
    String bomId,
    String targetItemId,
    BigDecimal targetQuantity,
    OffsetDateTime plannedStartAt,
    /** Optional BOM materials the order takes, by item; its BOM calculations plan them (ADR-033 decision 6). */
    List<String> optionalItemIds
) {

    public WorkOrderView {
        optionalItemIds = optionalItemIds == null ? List.of() : List.copyOf(optionalItemIds);
    }
}
