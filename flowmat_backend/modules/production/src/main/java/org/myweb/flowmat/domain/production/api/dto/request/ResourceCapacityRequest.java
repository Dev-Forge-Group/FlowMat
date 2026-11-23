package org.myweb.flowmat.domain.production.api.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** A project's whole list of resource limits; what is left out is removed (ADR-046 decision 5). */
public record ResourceCapacityRequest(@NotNull @Size(max = 20) List<@Valid Capacity> capacities) {

    /** {@code resourceType} energy or water; {@code per} hour or day; {@code unit} the one its plans count it in. */
    public record Capacity(String resourceType, String unit, BigDecimal amount, String per) {
    }
}
