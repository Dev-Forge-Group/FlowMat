package org.myweb.flowmat.domain.user.api.dto.request;

import jakarta.validation.constraints.NotBlank;

public record SocialLinkUnlinkRequest(
    @NotBlank String provider,
    @NotBlank String currentPassword
) {

    @Override
    public String toString() {
        return "SocialLinkUnlinkRequest[credentials=REDACTED]";
    }
}
