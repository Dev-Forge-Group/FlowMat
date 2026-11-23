package org.myweb.flowmat.domain.user.api.dto.request;

import jakarta.validation.constraints.NotBlank;

/** A signed-in user's new password (docs/operations/login-security.md §2): the current one proves it is them. */
public record PasswordChangeRequest(
    @NotBlank String currentPassword,
    @NotBlank String newPassword
) {

    @Override
    public String toString() {
        return "PasswordChangeRequest[credentials=REDACTED]";
    }
}
