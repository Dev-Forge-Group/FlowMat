package org.myweb.flowmat.domain.project.api.dto.response;

import java.time.OffsetDateTime;

public record ProjectMemberResponse(
    String projectMemberId,
    String projectId,
    String userId,
    String projectRole,
    String memberStatus,
    OffsetDateTime joinedAt
) {
}
