package org.myweb.flowmat.domain.connector.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.connector.api.dto.response.ConnectorProfileReadinessResponse;
import org.myweb.flowmat.domain.connector.application.ConnectorProfileReadinessService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/projects/{projectId}/http-profiles/by-version/{profileVersionId}")
public class ConnectorProfileReadinessController {
    private final ConnectorProfileReadinessService readiness;

    @GetMapping("/readiness")
    public ApiResponse<ConnectorProfileReadinessResponse> check(@PathVariable String projectId,
        @PathVariable String profileVersionId) {
        return ApiResponse.ok(readiness.check(projectId, profileVersionId));
    }
}
