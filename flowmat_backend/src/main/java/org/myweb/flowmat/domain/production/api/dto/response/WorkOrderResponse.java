package org.myweb.flowmat.domain.production.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record WorkOrderResponse(
    String workOrderId,
    String projectId,
    String workflowId,
    String workOrderNumber,
    String workOrderTitle,
    String workOrderStatus,
    String priority,
    String targetItemId,
    BigDecimal targetQuantity,
    OffsetDateTime plannedStartAt,
    OffsetDateTime plannedEndAt,
    OffsetDateTime actualStartAt,
    OffsetDateTime actualEndAt,
    String instruction,
    String assignedTo,
    String approvedBy,
    OffsetDateTime approvedAt,
    /** Sum of actual output from finished physical production runs started against this order. */
    BigDecimal producedQuantity,
    int runCount,
    String bomId,
    String instructionUrl,
    String equipmentId,
    /** Optional BOM materials the order takes, by item (ADR-033); empty for none. */
    List<String> optionalItemIds
) {
}
