package org.myweb.flowmat.domain.bom.api.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/** Both nullable date boundaries are supplied explicitly, including when clearing a boundary. */
public record BomEffectivityRequest(
    @JsonProperty(required = true) LocalDate effectiveFrom,
    @JsonProperty(required = true) LocalDate effectiveTo,
    @NotNull @Min(0) Long expectedPeriodVersion,
    @NotBlank @Size(max = 1000) String reason,
    @NotNull UUID requestId
) {}
