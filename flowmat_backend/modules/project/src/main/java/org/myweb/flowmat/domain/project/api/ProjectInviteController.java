package org.myweb.flowmat.domain.project.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.project.api.dto.request.ProjectInviteAcceptRequest;
import org.myweb.flowmat.domain.project.api.dto.request.ProjectInviteRequest;
import org.myweb.flowmat.domain.project.api.dto.response.ProjectInvitePreviewResponse;
import org.myweb.flowmat.domain.project.api.dto.response.ProjectInviteResponse;
import org.myweb.flowmat.domain.project.api.dto.response.ProjectMemberResponse;
import org.myweb.flowmat.domain.project.application.ProjectInviteService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/project-invites")
public class ProjectInviteController {

    private final ProjectInviteService projectInviteService;

    @GetMapping
    public ApiResponse<List<ProjectInviteResponse>> listInvites(@RequestParam("projectId") String projectId) {
        return ApiResponse.ok(projectInviteService.listInvites(projectId));
    }

    @PostMapping
    public ApiResponse<ProjectInviteResponse> createInvite(@Valid @RequestBody ProjectInviteRequest request) {
        return ApiResponse.ok(projectInviteService.createInvite(request));
    }

    @GetMapping("/preview")
    public ApiResponse<ProjectInvitePreviewResponse> previewInvite(@RequestParam("token") String token) {
        return ApiResponse.ok(projectInviteService.previewInvite(token));
    }

    @PostMapping("/accept")
    public ApiResponse<ProjectMemberResponse> acceptInvite(@Valid @RequestBody ProjectInviteAcceptRequest request) {
        return ApiResponse.ok(projectInviteService.acceptInvite(request));
    }

    @DeleteMapping("/{inviteId}")
    public ApiResponse<Void> cancelInvite(@PathVariable("inviteId") String inviteId) {
        projectInviteService.cancelInvite(inviteId);
        return ApiResponse.ok(null);
    }
}
