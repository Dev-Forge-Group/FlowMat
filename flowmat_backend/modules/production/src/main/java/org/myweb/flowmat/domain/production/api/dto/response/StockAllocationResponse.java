package org.myweb.flowmat.domain.production.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A work order's stock allocations (docs/domain/stock-allocation.md), oldest first. {@code plan} is filled only by an
 * allocate call: per item, what was needed, already allocated, allocated now and still short.
 */
public record StockAllocationResponse(
    String workOrderId,
    String workOrderNumber,
    String workOrderStatus,
    List<Allocation> allocations,
    List<PlanLine> plan
) {

    public record Allocation(
        String allocationId,
        String inventoryId,
        String itemId,
        String itemCode,
        String lotId,
        String lotNo,
        String location,
        BigDecimal quantity,
        BigDecimal consumedQuantity,
        BigDecimal releasedQuantity,
        BigDecimal remaining,
        String status,
        String createdBy,
        OffsetDateTime createdAt
    ) {
    }

    public record PlanLine(
        String itemId,
        String itemCode,
        BigDecimal needed,
        BigDecimal allocatedBefore,
        BigDecimal allocatedNow,
        BigDecimal shortage
    ) {
    }
}
