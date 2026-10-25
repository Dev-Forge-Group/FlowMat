package org.myweb.flowmat.domain.connector.api;

import java.io.InputStream;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.connector.api.dto.request.ConnectorProfileRequest;
import org.myweb.flowmat.domain.connector.api.dto.response.ConnectorProfileResponse;
import org.myweb.flowmat.domain.connector.api.dto.response.ConnectorProfileVersionResponse;
import org.myweb.flowmat.domain.connector.application.ConnectorProfileService;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/projects/{projectId}/http-profiles")
public class ConnectorProfileController {
    private final ConnectorProfileService profiles;
    private final ProjectAccessService access;

    @GetMapping
    public ApiResponse<List<ConnectorProfileResponse>> list(@PathVariable("projectId") String projectId) {
        return ApiResponse.ok(profiles.list(projectId));
    }

    @GetMapping("/{profileId}")
    public ApiResponse<ConnectorProfileResponse> get(@PathVariable("projectId") String projectId, @PathVariable("profileId") String profileId) {
        return ApiResponse.ok(profiles.get(projectId, profileId));
    }

    @GetMapping("/{profileId}/versions")
    public ApiResponse<List<ConnectorProfileVersionResponse>> history(@PathVariable("projectId") String projectId, @PathVariable("profileId") String profileId) {
        return ApiResponse.ok(profiles.history(projectId, profileId));
    }

    @GetMapping("/by-version/{profileVersionId}/metadata")
    public ApiResponse<ConnectorProfileVersionResponse> versionMetadata(@PathVariable("projectId") String projectId,
        @PathVariable("profileVersionId") String profileVersionId) {
        return ApiResponse.ok(profiles.versionMetadata(projectId, profileVersionId));
    }

    @PostMapping
    public ApiResponse<ConnectorProfileResponse> create(@PathVariable("projectId") String projectId, InputStream body) {
        access.requireProjectOwnerAccess(projectId);
        ConnectorProfileRequest request = ConnectorProfileRequest.read(body, false);
        return ApiResponse.ok(profiles.create(projectId, request));
    }

    @PutMapping("/{profileId}")
    public ApiResponse<ConnectorProfileResponse> update(@PathVariable("projectId") String projectId, @PathVariable("profileId") String profileId,
                                                       InputStream body) {
        access.requireProjectOwnerAccess(projectId);
        ConnectorProfileRequest request = ConnectorProfileRequest.read(body, true);
        return ApiResponse.ok(profiles.update(projectId, profileId, request));
    }

    @PatchMapping("/{profileId}/status")
    public ApiResponse<ConnectorProfileResponse> status(@PathVariable("projectId") String projectId, @PathVariable("profileId") String profileId,
                                                       InputStream body) {
        access.requireProjectOwnerAccess(projectId);
        ConnectorProfileRequest.Status status = ConnectorProfileRequest.readStatus(body);
        return ApiResponse.ok(profiles.status(projectId, profileId, status.expectedVersion(), status.enabled()));
    }

    @DeleteMapping("/{profileId}")
    public ApiResponse<Void> delete(@PathVariable("projectId") String projectId, @PathVariable("profileId") String profileId,
                                   @RequestParam(value = "expectedVersion", required = false) String expectedVersion) {
        access.requireProjectOwnerAccess(projectId);
        profiles.delete(projectId, profileId, ConnectorProfileRequest.queryVersion(expectedVersion));
        return ApiResponse.ok(null);
    }
}
