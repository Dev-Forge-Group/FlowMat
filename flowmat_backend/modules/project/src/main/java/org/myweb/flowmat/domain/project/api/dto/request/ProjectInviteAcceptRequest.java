package org.myweb.flowmat.domain.project.api.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ProjectInviteAcceptRequest(@NotBlank String inviteToken) {
}
