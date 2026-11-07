package org.myweb.flowmat.domain.production.api.dto.request;

import java.util.UUID;

/**
 * An actual setup of a run (docs/domain/equipment-setup-cost.md AS1).
 *
 * @param requestId    the client's key; sending the same setup again returns the run as it is now
 * @param equipmentId  the equipment it was on; defaults to the run's work order's equipment
 * @param setupMinutes whole minutes, 1 to 1440
 * @param note         optional, at most 500 characters
 */
public record RunSetupRequest(UUID requestId, String equipmentId, Integer setupMinutes, String note) {
}
