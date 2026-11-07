package org.myweb.flowmat.domain.production.api.dto.request;

/** Why recorded worked time does not count (docs/domain/worker-calendar.md LB3). */
public record RunLaborCancelRequest(String reason) {
}
