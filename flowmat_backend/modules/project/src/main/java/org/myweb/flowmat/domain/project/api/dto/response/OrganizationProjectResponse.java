package org.myweb.flowmat.domain.project.api.dto.response;

/**
 * Management metadata of a project in an organization, for its owners and admins only (docs/domain/organization.md OR3):
 * name, status and owner. It carries no business data and grants no project access.
 */
public record OrganizationProjectResponse(String projectId, String projectName, String projectStatus, String ownerId) {
}
