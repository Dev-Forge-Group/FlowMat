package org.myweb.flowmat.domain.production.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Fails a running process step; the connection's failure policy then applies as in a graph run (ADR-011). */
public record ProductionRunStepFailRequest(
    @NotBlank(message = "errorCode is required.") @Size(max = 100) String errorCode,
    @Size(max = 4000) String errorMessage
) {
}
