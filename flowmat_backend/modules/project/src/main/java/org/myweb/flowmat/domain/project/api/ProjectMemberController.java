package org.myweb.flowmat.domain.project.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.project.api.dto.request.ProjectMemberRoleUpdateRequest;
import org.myweb.flowmat.domain.project.api.dto.response.ProjectMemberResponse;
import org.myweb.flowmat.domain.project.application.ProjectMemberService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/project-members")
public class ProjectMemberController {

    private final ProjectMemberService projectMemberService;

    @GetMapping
    public ApiResponse<List<ProjectMemberResponse>> listMembers(@RequestParam("projectId") String projectId) {
        return ApiResponse.ok(projectMemberService.listMembers(projectId));
    }

    @PutMapping("/{projectMemberId}/role")
    public ApiResponse<ProjectMemberResponse> updateRole(
        @PathVariable("projectMemberId") String projectMemberId,
        @Valid @RequestBody ProjectMemberRoleUpdateRequest request
    ) {
        return ApiResponse.ok(projectMemberService.updateMemberRole(projectMemberId, request));
    }

    @DeleteMapping("/{projectMemberId}")
    public ApiResponse<Void> removeMember(@PathVariable("projectMemberId") String projectMemberId) {
        projectMemberService.removeMember(projectMemberId);
        return ApiResponse.ok(null);
    }
}
