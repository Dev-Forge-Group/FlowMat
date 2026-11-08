package org.myweb.flowmat.domain.project.api.dto.response;

import java.time.OffsetDateTime;

public record ProjectInviteResponse(
    String inviteId,
    String projectId,
    String invitedEmail,
    String invitedUserId,
    String projectRole,
    String inviteStatus,
    String inviteToken,
    OffsetDateTime acceptedAt,
    OffsetDateTime expiredAt
) {
}
