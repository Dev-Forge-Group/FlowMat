package org.myweb.flowmat.domain.user.api.dto.request;

public record RefreshTokenRequest(String refreshToken, String deviceId) {

    @Override
    public String toString() {
        return "RefreshTokenRequest[credentials=REDACTED]";
    }
}
