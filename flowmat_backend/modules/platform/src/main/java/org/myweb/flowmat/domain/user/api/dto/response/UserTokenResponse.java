package org.myweb.flowmat.domain.user.api.dto.response;

public record UserTokenResponse(
    String accessToken,
    String refreshToken,
    String deviceId,
    boolean additionalInfoRequired
) {

    @Override
    public String toString() {
        return "UserTokenResponse[credentials=REDACTED]";
    }
}
