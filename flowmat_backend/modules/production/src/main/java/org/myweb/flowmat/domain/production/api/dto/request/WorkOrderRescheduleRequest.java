package org.myweb.flowmat.domain.production.api.dto.request;

import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Full replacement of planned dates, with the original plan and a stable retry key. Null dates clear the plan. */
public record WorkOrderRescheduleRequest(
    @NotNull UUID requestId,
    OffsetDateTime expectedPlannedStartAt,
    OffsetDateTime expectedPlannedEndAt,
    OffsetDateTime plannedStartAt,
    OffsetDateTime plannedEndAt,
    String reason
) {}
