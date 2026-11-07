package org.myweb.flowmat.domain.project.api.dto.response;

/** An organization and the current user's role in it (docs/domain/organization.md OR2). */
public record OrganizationResponse(String organizationId, String organizationName, String organizationType, String ownerUserId,
    String myRole) {
}
