package org.myweb.flowmat.domain.production.api.dto.request;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** Replaces the editable fields of a {@code draft} work order; approved orders are frozen. */
public record WorkOrderUpdateRequest(
    String workOrderTitle,
    String workflowId,
    String targetItemId,
    BigDecimal targetQuantity,
    String priority,
    OffsetDateTime plannedStartAt,
    OffsetDateTime plannedEndAt,
    String instruction,
    String assignedTo,
    /** BOM revision the order produces with; null clears it. Must be approved before the order is approved. */
    String bomId,
    /** Link to the written work instruction; null clears it. http or https, at most 255 characters. */
    String instructionUrl,
    /** Optional BOM materials the order takes, by item (ADR-033); null leaves them as they are, empty takes none. */
    List<String> optionalItemIds
) {

    public WorkOrderUpdateRequest(String workOrderTitle, String workflowId, String targetItemId, BigDecimal targetQuantity,
                                  String priority, OffsetDateTime plannedStartAt, OffsetDateTime plannedEndAt, String instruction,
                                  String assignedTo, String bomId, String instructionUrl) {
        this(workOrderTitle, workflowId, targetItemId, targetQuantity, priority, plannedStartAt, plannedEndAt, instruction,
            assignedTo, bomId, instructionUrl, null);
    }
}
