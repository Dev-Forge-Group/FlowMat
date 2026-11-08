package org.myweb.flowmat.domain.workflow.api.dto.request;

import jakarta.validation.constraints.NotNull;

public record ProcessPositionRequest(
    @NotNull Double posX,
    @NotNull Double posY
) {
}
