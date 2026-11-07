package org.myweb.flowmat.domain.production.api.dto.request;

import java.util.UUID;

/**
 * Equipment running time of a run (docs/domain/equipment-setup-cost.md ER2).
 *
 * @param requestId      the client's key; sending the same record again returns the run as it is now
 * @param equipmentId    the equipment that ran; defaults to the run's work order's equipment
 * @param runningMinutes whole minutes, 1 to 10080 (7 days)
 * @param note           optional, at most 500 characters
 */
public record RunEquipmentTimeRequest(UUID requestId, String equipmentId, Integer runningMinutes, String note) {
}
