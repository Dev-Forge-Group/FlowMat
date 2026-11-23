package org.myweb.flowmat.domain.production.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** One confirmed plan of a workflow, newest first (docs/architecture/adr/ADR-043-plan-backwards-from-a-target.md decision 7). */
public record PlanConfirmationSummaryResponse(
    String requestId,
    String revisionId,
    String targetPortId,
    String targetConnectionId,
    BigDecimal targetQuantity,
    String workOrderId,
    List<String> purchaseOrderIds,
    OffsetDateTime confirmedAt
) {
}
