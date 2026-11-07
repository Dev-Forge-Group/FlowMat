package org.myweb.flowmat.domain.project.api.dto.request;

/** A team organization; its creator becomes its owner (docs/domain/organization.md OR2). */
public record OrganizationCreateRequest(String organizationName) {
}
