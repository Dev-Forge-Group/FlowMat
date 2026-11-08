package org.myweb.flowmat.domain.user.api.dto.request;

import jakarta.validation.constraints.NotBlank;

public record DormantTokenRequest(
    @NotBlank String token
) {

    @Override
    public String toString() {
        return "DormantTokenRequest[credentials=REDACTED]";
    }
}
