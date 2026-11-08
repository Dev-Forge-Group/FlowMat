package org.myweb.flowmat.domain.production.api.dto.request;

/** Why a recorded setup does not count (docs/domain/equipment-setup-cost.md AS4). */
public record RunSetupCancelRequest(String reason) {
}
