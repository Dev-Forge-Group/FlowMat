package org.myweb.flowmat.domain.production.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record ProductionRunStartRequest(
    @NotBlank String projectId,
    @NotBlank String workflowId,
    String targetItemId,
    @NotNull BigDecimal plannedOutputQty,
    String runType,
    String startedBy,
    /** Optional; the order must be approved or in progress, and supplies the target item when none is given. */
    String workOrderId,
    /** Optional; an approved BOM revision whose requirements are frozen onto the run. Defaults to the work order's BOM. */
    String bomId,
    /** Optional published workflow revision. When omitted, the latest published revision is used if one exists. */
    String workflowRevisionId
) {
}
