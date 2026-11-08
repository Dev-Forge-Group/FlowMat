package org.myweb.flowmat.domain.user.api.dto.request;

public record LogoutRequest(String refreshToken) {

    @Override
    public String toString() {
        return "LogoutRequest[credentials=REDACTED]";
    }
}
