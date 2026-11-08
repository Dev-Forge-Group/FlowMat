package org.myweb.flowmat.domain.project.api.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ProjectCreateRequest(
    @NotBlank String projectName,
    @NotBlank String ownerId,
    String projectDesc,
    String visibility,
    /** Optional; the creator must belong to it. Default: the creator's personal organization (docs/domain/organization.md OR6). */
    String organizationId
) {

    public ProjectCreateRequest(String projectName, String ownerId, String projectDesc, String visibility) {
        this(projectName, ownerId, projectDesc, visibility, null);
    }
}
