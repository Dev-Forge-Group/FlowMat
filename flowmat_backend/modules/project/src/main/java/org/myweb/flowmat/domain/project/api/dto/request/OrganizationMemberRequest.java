package org.myweb.flowmat.domain.project.api.dto.request;

/** A user to add with a role (owner, admin or member), or only a new role (docs/domain/organization.md OR2). */
public record OrganizationMemberRequest(String userId, String orgRole) {
}
