package org.myweb.flowmat.domain.production.api.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * What the worked time of a work order's physical runs cost, for the project owner only
 * (docs/architecture/adr/ADR-025-worker-labor-cost.md).
 */
public record WorkOrderLaborCostResponse(String workOrderId, int runs, int workedMinutes, BigDecimal cost, boolean costComplete,
                                         List<RunLaborCostResponse.Person> people, BigDecimal premiumCost, boolean premiumsComplete) {
}
