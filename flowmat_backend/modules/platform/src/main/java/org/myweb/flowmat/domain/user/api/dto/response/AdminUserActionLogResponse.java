package org.myweb.flowmat.domain.user.api.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminUserActionLogResponse(
    UUID actionLogId,
    String actorUserId,
    String targetUserId,
    String actionType,
    String previousValue,
    String newValue,
    String reason,
    OffsetDateTime createdAt
) {
}
