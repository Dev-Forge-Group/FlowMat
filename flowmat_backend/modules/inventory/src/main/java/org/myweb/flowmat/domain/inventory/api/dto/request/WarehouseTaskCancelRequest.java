package org.myweb.flowmat.domain.inventory.api.dto.request;

import jakarta.validation.constraints.Size;

public record WarehouseTaskCancelRequest(@Size(max = 500) String reason) {
}
