package org.myweb.flowmat.domain.production.api.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.Map;

/**
 * Confirms a plan seen before (docs/architecture/adr/ADR-043-plan-backwards-from-a-target.md decision 7): the same target,
 * the {@code basis} that plan returned, and a {@code requestId} that makes sending it again safe. {@code supplierName} is
 * who the draft purchase order for shortages is to; needed only when something is short.
 */
public record PlanConfirmRequest(
    @NotNull @Valid PlanPreviewRequest.Target target,
    @NotNull Basis basis,
    @NotBlank String requestId,
    String supplierName,
    Map<String, String> exclusiveChoices
) {

    public record Basis(@NotBlank String revisionId, Map<String, BigDecimal> available) {
    }
}
