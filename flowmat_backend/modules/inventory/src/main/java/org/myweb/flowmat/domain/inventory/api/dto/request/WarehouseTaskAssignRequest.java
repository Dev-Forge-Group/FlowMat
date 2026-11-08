package org.myweb.flowmat.domain.inventory.api.dto.request;

import jakarta.validation.constraints.Size;

/** Who should do an open warehouse task (docs/domain/warehouse-task.md W7); blank leaves it to no one. */
public record WarehouseTaskAssignRequest(@Size(max = 50) String assignedTo) {
}
