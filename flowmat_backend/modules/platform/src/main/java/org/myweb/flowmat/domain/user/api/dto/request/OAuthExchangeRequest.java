package org.myweb.flowmat.domain.user.api.dto.request;

import jakarta.validation.constraints.NotBlank;

public record OAuthExchangeRequest(@NotBlank String code) {

    @Override
    public String toString() {
        return "OAuthExchangeRequest[credentials=REDACTED]";
    }
}
