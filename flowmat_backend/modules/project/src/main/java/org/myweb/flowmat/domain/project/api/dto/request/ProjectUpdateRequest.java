package org.myweb.flowmat.domain.project.api.dto.request;

public record ProjectUpdateRequest(String projectName, String projectDesc, String visibility) {
}
