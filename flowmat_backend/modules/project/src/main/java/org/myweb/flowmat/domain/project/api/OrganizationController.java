package org.myweb.flowmat.domain.project.api;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.project.api.dto.request.OrganizationCreateRequest;
import org.myweb.flowmat.domain.project.api.dto.request.OrganizationMemberRequest;
import org.myweb.flowmat.domain.project.api.dto.response.OrganizationMemberResponse;
import org.myweb.flowmat.domain.project.api.dto.response.OrganizationProjectResponse;
import org.myweb.flowmat.domain.project.api.dto.response.OrganizationResponse;
import org.myweb.flowmat.domain.project.application.OrganizationService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Organizations, ADR-001 Phase 1 (docs/domain/organization.md OR2-OR4). None of these grant project access. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/organizations")
public class OrganizationController {
    private final OrganizationService organizations;

    @PostMapping
    public ApiResponse<OrganizationResponse> create(@RequestBody(required = false) OrganizationCreateRequest request) {
        return ApiResponse.ok(organizations.create(request));
    }

    @GetMapping
    public ApiResponse<List<OrganizationResponse>> mine() {
        return ApiResponse.ok(organizations.mine());
    }

    @GetMapping("/{organizationId}")
    public ApiResponse<OrganizationResponse> get(@PathVariable("organizationId") String organizationId) {
        return ApiResponse.ok(organizations.get(organizationId));
    }

    @GetMapping("/{organizationId}/members")
    public ApiResponse<List<OrganizationMemberResponse>> members(@PathVariable("organizationId") String organizationId) {
        return ApiResponse.ok(organizations.members(organizationId));
    }

    @PostMapping("/{organizationId}/members")
    public ApiResponse<OrganizationMemberResponse> add(
        @PathVariable("organizationId") String organizationId,
        @RequestBody(required = false) OrganizationMemberRequest request
    ) {
        return ApiResponse.ok(organizations.add(organizationId, request));
    }

    @PutMapping("/{organizationId}/members/{organizationMemberId}")
    public ApiResponse<OrganizationMemberResponse> changeRole(
        @PathVariable("organizationId") String organizationId,
        @PathVariable("organizationMemberId") String organizationMemberId,
        @RequestBody(required = false) OrganizationMemberRequest request
    ) {
        return ApiResponse.ok(organizations.changeRole(organizationId, organizationMemberId, request));
    }

    @PostMapping("/{organizationId}/members/{organizationMemberId}/leave")
    public ApiResponse<OrganizationMemberResponse> leave(
        @PathVariable("organizationId") String organizationId,
        @PathVariable("organizationMemberId") String organizationMemberId
    ) {
        return ApiResponse.ok(organizations.leave(organizationId, organizationMemberId));
    }

    @PostMapping("/{organizationId}/members/{organizationMemberId}/remove")
    public ApiResponse<OrganizationMemberResponse> remove(
        @PathVariable("organizationId") String organizationId,
        @PathVariable("organizationMemberId") String organizationMemberId
    ) {
        return ApiResponse.ok(organizations.remove(organizationId, organizationMemberId));
    }

    /** Management metadata for owners and admins (OR3); not a way into the projects. */
    @GetMapping("/{organizationId}/projects")
    public ApiResponse<List<OrganizationProjectResponse>> projects(@PathVariable("organizationId") String organizationId) {
        return ApiResponse.ok(organizations.projects(organizationId));
    }
}
