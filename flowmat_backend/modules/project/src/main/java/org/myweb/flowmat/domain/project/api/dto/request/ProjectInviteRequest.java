package org.myweb.flowmat.domain.project.api.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ProjectInviteRequest(
    @NotBlank String projectId,
    @Email @NotBlank String invitedEmail,
    String projectRole
) {
}
