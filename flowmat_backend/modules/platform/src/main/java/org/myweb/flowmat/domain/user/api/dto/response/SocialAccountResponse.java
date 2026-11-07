package org.myweb.flowmat.domain.user.api.dto.response;

import java.time.OffsetDateTime;

public record SocialAccountResponse(
    Long socialAccountId,
    String provider,
    String providerEmail,
    OffsetDateTime createdAt
) {
}
