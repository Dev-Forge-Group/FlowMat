package org.myweb.flowmat.domain.user.api.dto.request;

import jakarta.validation.constraints.NotBlank;

public record SocialLinkStartRequest(
    @NotBlank String provider,
    @NotBlank String currentPassword,
    String returnTo
) {

    @Override
    public String toString() {
        return "SocialLinkStartRequest[credentials=REDACTED]";
    }
}
