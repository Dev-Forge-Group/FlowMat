package org.myweb.flowmat.domain.workflow.api.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * A node's execution requirements, all of them, in order (docs/architecture/adr/ADR-043-plan-backwards-from-a-target.md
 * decision 4). They replace what the node had; an empty list clears them.
 */
public record ProcessRequirementsRequest(@NotNull @Size(max = 20) List<@Valid @NotNull Requirement> requirements) {

    /**
     * {@code kind} is equipment, crew, skill, compute or time; {@code ref} the equipment (of the node's project) or what
     * else it names; {@code per} batch (the default) or output_unit; a time's {@code unit} is s, min (the default) or h.
     */
    public record Requirement(@NotNull String kind, @Size(max = 50) String ref, @NotNull BigDecimal amount,
        @Size(max = 20) String unit, String per) {
    }
}
