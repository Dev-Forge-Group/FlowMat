package org.myweb.flowmat.domain.inventory.api.dto.request;

import java.math.BigDecimal;

public record InventoryReserveRequest(String inventoryId, BigDecimal reserveQuantity) {
}
