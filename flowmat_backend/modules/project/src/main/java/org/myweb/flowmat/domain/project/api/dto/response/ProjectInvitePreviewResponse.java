package org.myweb.flowmat.domain.project.api.dto.response;

import java.time.OffsetDateTime;

/**
 * What an invitee sees before accepting: enough to decide, without exposing the full invited email or the
 * token-holder's identity to other accounts.
 */
public record ProjectInvitePreviewResponse(
    String projectName,
    String projectRole,
    String inviterName,
    String invitedEmailMasked,
    String inviteStatus,
    OffsetDateTime expiredAt,
    boolean expired,
    boolean addressedToCurrentUser
) {
}
