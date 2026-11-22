package org.myweb.flowmat.domain.production.api.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.Map;

/**
 * A plan to see (docs/architecture/adr/ADR-043-plan-backwards-from-a-target.md decision 2): the target at an output port, or
 * at a connection whose arrival counts; the revision (the latest published when left out); which way Exclusive nodes go.
 */
public record PlanPreviewRequest(
    String revisionId,
    @NotNull @Valid Target target,
    Map<String, String> exclusiveChoices
) {

    /** {@code unit} null means the port's or connection's own. */
    public record Target(String processIoId, String connectionId, @NotNull BigDecimal quantity, String unit) {
    }
}
