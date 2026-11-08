package org.myweb.flowmat.domain.production.api.dto.request;

/** Why recorded running time does not count (docs/domain/equipment-setup-cost.md ER3). */
public record RunEquipmentTimeCancelRequest(String reason) {
}
