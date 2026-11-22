package org.myweb.flowmat.domain.user.application;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;

/**
 * What a new password must be (docs/operations/login-security.md): at least 8 characters, at most 72 bytes (BCrypt reads no
 * more, so a longer one would be cut without the user knowing), and not the account's own ID or email. Checked where a
 * password is set (signup, social signup, reset and profile change), never at login, so existing passwords keep working.
 */
final class PasswordPolicy {

    static final int MIN_LENGTH = 8;
    static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    /** The password as it will be stored, or a 400 saying what is wrong with it. */
    static String require(String password, String userId, String email) {
        String value = password == null ? "" : password.trim();
        if (value.codePointCount(0, value.length()) < MIN_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Password must be at least " + MIN_LENGTH + " characters.");
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Password must be at most " + MAX_BYTES + " bytes.");
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (same(lower, userId) || same(lower, email)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Password must not be the user ID or email.");
        }
        return value;
    }

    private static boolean same(String lower, String other) {
        return other != null && lower.equals(other.trim().toLowerCase(Locale.ROOT));
    }
}
