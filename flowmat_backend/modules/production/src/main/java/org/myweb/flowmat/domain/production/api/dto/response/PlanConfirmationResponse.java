package org.myweb.flowmat.domain.production.api.dto.response;

import java.util.List;

/** What a confirmation made: a draft work order and, when something was short, one draft purchase order. */
public record PlanConfirmationResponse(String requestId, String revisionId, String workOrderId, List<String> purchaseOrderIds) {
}
