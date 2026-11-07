package org.myweb.flowmat.global.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "Invalid request."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication is required."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "You do not have permission."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Authentication service is temporarily unavailable."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Please try again later."),
    CONFLICT(HttpStatus.CONFLICT, "This record was changed by someone else. Reload and try again."),

    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Token is invalid."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Token has expired."),
    TOKEN_TYPE_INVALID(HttpStatus.UNAUTHORIZED, "Token type is invalid."),

    DUPLICATE_USER_ID(HttpStatus.BAD_REQUEST, "User ID is already in use."),
    DUPLICATE_EMAIL(HttpStatus.BAD_REQUEST, "Email is already in use."),
    DUPLICATE_TEL(HttpStatus.BAD_REQUEST, "Phone number is already in use."),
    DUPLICATE_NICKNAME(HttpStatus.BAD_REQUEST, "Nickname is already in use."),
    EMAIL_NOT_VERIFIED(HttpStatus.BAD_REQUEST, "Email verification is required."),
    EMAIL_CODE_INVALID(HttpStatus.BAD_REQUEST, "Email verification code is invalid."),
    EMAIL_CODE_COOLDOWN(HttpStatus.TOO_MANY_REQUESTS, "Please wait before requesting another email code."),
    DORMANT_ACCOUNT(HttpStatus.FORBIDDEN, "This account is dormant and must be reactivated."),
    DORMANT_TOKEN_INVALID(HttpStatus.BAD_REQUEST, "Dormant account reactivation token is invalid."),
    PASSWORD_RESET_TOKEN_INVALID(HttpStatus.BAD_REQUEST, "Password reset token is invalid."),
    PASSWORD_RESET_TOKEN_EXPIRED(HttpStatus.BAD_REQUEST, "Password reset token has expired."),
    INVALID_PASSWORD(HttpStatus.BAD_REQUEST, "Password is incorrect."),
    REFRESH_TOKEN_NOT_FOUND(HttpStatus.UNAUTHORIZED, "Refresh token was not found."),
    TOKEN_REUSE_DETECTED(HttpStatus.UNAUTHORIZED, "Refresh token reuse was detected."),
    FACE_NOT_REGISTERED(HttpStatus.BAD_REQUEST, "No face data is registered."),
    FACE_NOT_RECOGNIZED(HttpStatus.BAD_REQUEST, "Face could not be recognized."),
    FACE_ACCOUNT_LOCKED(HttpStatus.FORBIDDEN, "Face login is temporarily locked."),
    FACE_LOGIN_DISABLED(HttpStatus.NOT_FOUND, "Face login is not enabled.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }
}
