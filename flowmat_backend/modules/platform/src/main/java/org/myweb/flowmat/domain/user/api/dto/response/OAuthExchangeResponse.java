package org.myweb.flowmat.domain.user.api.dto.response;

public record OAuthExchangeResponse(
    String resultType,
    String accessToken,
    String signupToken,
    String provider,
    String deviceId,
    boolean additionalInfoRequired
) {

    @Override
    public String toString() {
        return "OAuthExchangeResponse[credentials=REDACTED]";
    }
}
