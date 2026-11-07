package org.myweb.flowmat.domain.project.api.dto.response;

public record ProjectResponse(
    String projectId,
    String projectName,
    String projectDesc,
    String projectStatus,
    String visibility,
    String currentWorkflowId,
    String timeZone,
    /** The organization it belongs to (docs/domain/organization.md); null for a project not linked yet. */
    String organizationId
) {
}
