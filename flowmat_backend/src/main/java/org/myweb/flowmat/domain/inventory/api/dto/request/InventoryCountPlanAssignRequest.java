package org.myweb.flowmat.domain.inventory.api.dto.request;

/** Who should count an open plan (docs/domain/stock-count.md P9); blank gives it to no one. */
public record InventoryCountPlanAssignRequest(String assignedTo) {
}
