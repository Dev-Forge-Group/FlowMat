package org.myweb.flowmat.domain.production.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One resource limit of a project (ADR-046 decision 5). */
public record ResourceCapacityResponse(String resourceType, String unit, BigDecimal amount, String per, String updatedBy,
    OffsetDateTime updatedAt) {
}
