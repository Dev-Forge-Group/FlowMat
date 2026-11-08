package org.myweb.flowmat.domain.project.api.dto.response;

import java.time.OffsetDateTime;

public record OrganizationMemberResponse(String organizationMemberId, String userId, String orgRole, String memberStatus,
    OffsetDateTime joinedAt, OffsetDateTime leftAt) {
}
