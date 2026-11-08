package org.myweb.flowmat.domain.project.application;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.project.api.dto.request.OrganizationCreateRequest;
import org.myweb.flowmat.domain.project.api.dto.request.OrganizationMemberRequest;
import org.myweb.flowmat.domain.project.api.dto.response.OrganizationMemberResponse;
import org.myweb.flowmat.domain.project.api.dto.response.OrganizationProjectResponse;
import org.myweb.flowmat.domain.project.api.dto.response.OrganizationResponse;
import org.myweb.flowmat.domain.project.domain.entity.Organization;
import org.myweb.flowmat.domain.project.domain.entity.OrganizationMember;
import org.myweb.flowmat.domain.project.domain.entity.Project;
import org.myweb.flowmat.domain.project.domain.entity.ProjectMember;
import org.myweb.flowmat.domain.project.repository.OrganizationMemberRepository;
import org.myweb.flowmat.domain.project.repository.OrganizationRepository;
import org.myweb.flowmat.domain.project.repository.ProjectMemberRepository;
import org.myweb.flowmat.domain.project.repository.ProjectRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Organizations, ADR-001 Phase 1-3 (docs/domain/organization.md OR1-OR8). Nothing here grants project access:
 * {@link ProjectAccessService} still decides it from the project owner and active project members only.
 */
@Service
@RequiredArgsConstructor
public class OrganizationService {
    private static final String NOT_DELETED = "N";
    private static final String ACTIVE = OrganizationMember.ACTIVE;
    private static final Set<String> ROLES = Set.of(OrganizationMember.OWNER, OrganizationMember.ADMIN, OrganizationMember.MEMBER);
    private static final int MAX_NAME = 100;

    private final OrganizationRepository organizations;
    private final OrganizationMemberRepository members;
    private final ProjectRepository projects;
    private final ProjectMemberRepository projectMembers;
    private final ProjectAccessService access;
    private final IdGenerator ids;
    private final JdbcTemplate jdbc;

    @Transactional
    public OrganizationResponse create(OrganizationCreateRequest request) {
        String actor = access.requireCurrentUserId();
        String name = request == null || request.organizationName() == null ? null : request.organizationName().trim();
        if (name == null || name.isEmpty() || name.length() > MAX_NAME) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "organizationName is required, at most " + MAX_NAME + " characters.");
        }
        Organization organization = organization(name, Organization.TEAM, actor);
        member(organization.getOrganizationId(), actor, OrganizationMember.OWNER);
        return response(organization, OrganizationMember.OWNER);
    }

    /** The organizations the current user is an active member of; nobody sees the others (OR2). */
    @Transactional(readOnly = true)
    public List<OrganizationResponse> mine() {
        String actor = access.requireCurrentUserId();
        Map<String, String> roles = members.findAllByUserIdAndMemberStatus(actor, ACTIVE).stream()
            .collect(Collectors.toMap(OrganizationMember::getOrganizationId, OrganizationMember::getOrgRole, (a, b) -> a));
        if (roles.isEmpty()) return List.of();
        return organizations.findAllByOrganizationIdInAndDeletedYnOrderByOrganizationNameAsc(roles.keySet(), NOT_DELETED).stream()
            .map(one -> response(one, roles.get(one.getOrganizationId()))).toList();
    }

    @Transactional(readOnly = true)
    public OrganizationResponse get(String organizationId) {
        Organization organization = live(organizationId);
        return response(organization, requireMember(organization).getOrgRole());
    }

    @Transactional(readOnly = true)
    public List<OrganizationMemberResponse> members(String organizationId) {
        Organization organization = live(organizationId);
        requireMember(organization);
        return members.findAllByOrganizationIdAndMemberStatusOrderByJoinedAtAscOrganizationMemberIdAsc(organizationId, ACTIVE).stream()
            .map(OrganizationService::response).toList();
    }

    /** Name, status and owner of the organization's projects, for its owners and admins only (OR3). No business data. */
    @Transactional(readOnly = true)
    public List<OrganizationProjectResponse> projects(String organizationId) {
        Organization organization = live(organizationId);
        requireManager(requireMember(organization));
        return projects.findAllByOrganizationIdAndDeletedYnOrderByCreatedAtDesc(organizationId, NOT_DELETED).stream()
            .map(project -> new OrganizationProjectResponse(project.getProjectId(), project.getProjectName(),
                project.getProjectStatus(), project.getOwnerId()))
            .toList();
    }

    /** Owners add any role, admins add admins and members. An unknown user is refused by the user foreign key (OR2). */
    @Transactional
    public OrganizationMemberResponse add(String organizationId, OrganizationMemberRequest request) {
        Organization organization = locked(organizationId);
        OrganizationMember actor = requireManager(requireMember(organization));
        String userId = request == null || request.userId() == null ? null : request.userId().trim();
        if (userId == null || userId.isEmpty()) throw new BusinessException(ErrorCode.BAD_REQUEST, "userId is required.");
        String role = role(request.orgRole());
        if (OrganizationMember.OWNER.equals(role) && !OrganizationMember.OWNER.equals(actor.getOrgRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Only an organization owner can add an owner.");
        }
        if (members.existsByOrganizationIdAndUserIdAndMemberStatus(organizationId, userId, ACTIVE)) {
            throw new BusinessException(ErrorCode.CONFLICT, "User " + userId + " is already a member.");
        }
        try {
            return response(member(organizationId, userId, role));
        } catch (DataIntegrityViolationException unknownUser) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "User " + userId + " does not exist.");
        }
    }

    /** Owners change roles; the last owner cannot be demoted (OR2). */
    @Transactional
    public OrganizationMemberResponse changeRole(String organizationId, String organizationMemberId, OrganizationMemberRequest request) {
        Organization organization = locked(organizationId);
        OrganizationMember actor = requireMember(organization);
        if (!OrganizationMember.OWNER.equals(actor.getOrgRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Only an organization owner can change roles.");
        }
        OrganizationMember target = activeMember(organizationId, organizationMemberId);
        String role = role(request == null ? null : request.orgRole());
        if (OrganizationMember.OWNER.equals(target.getOrgRole()) && !OrganizationMember.OWNER.equals(role) && lastOwner(organizationId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "The last owner cannot be demoted; make another member owner first.");
        }
        target.setOrgRole(role);
        return response(members.save(target));
    }

    /** A member leaves on their own (OR4). */
    @Transactional
    public OrganizationMemberResponse leave(String organizationId, String organizationMemberId) {
        Organization organization = locked(organizationId);
        OrganizationMember actor = requireMember(organization);
        if (!actor.getOrganizationMemberId().equals(organizationMemberId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Members can only leave for themselves; ask an owner or admin to remove others.");
        }
        return end(organizationId, actor, "left");
    }

    /** Owners remove anyone, admins remove members (OR4). */
    @Transactional
    public OrganizationMemberResponse remove(String organizationId, String organizationMemberId) {
        Organization organization = locked(organizationId);
        OrganizationMember actor = requireManager(requireMember(organization));
        OrganizationMember target = activeMember(organizationId, organizationMemberId);
        if (!OrganizationMember.OWNER.equals(actor.getOrgRole()) && !OrganizationMember.MEMBER.equals(target.getOrgRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Admins can remove members only.");
        }
        return end(organizationId, target, "removed");
    }

    /**
     * The organization for a new project (OR6, confirmed 2026-10-10): the requested one if the creator is an active member
     * of it, else the creator's personal organization, made on first use. Runs inside the project creation.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String forNewProject(String creatorUserId, String requestedOrganizationId) {
        String requested = requestedOrganizationId == null || requestedOrganizationId.isBlank() ? null : requestedOrganizationId.trim();
        if (requested == null) {
            return personal(creatorUserId).getOrganizationId();
        }
        // Membership revocation and project creation must use the same lock (OR4/OR6).
        Organization organization = organizations.findForUpdate(requested)
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "Organization does not exist."));
        if (!members.existsByOrganizationIdAndUserIdAndMemberStatus(organization.getOrganizationId(), creatorUserId, ACTIVE)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Projects can only be created in an organization you belong to.");
        }
        return organization.getOrganizationId();
    }

    private Organization personal(String userId) {
        // One personal organization per user, even when two first projects are created at once.
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, "personal-organization|" + userId);
        return organizations.findByOwnerUserIdAndOrganizationTypeAndDeletedYn(userId, Organization.PERSONAL, NOT_DELETED)
            .orElseGet(() -> {
                Organization created = organization(userId + "의 작업 공간", Organization.PERSONAL, userId);
                member(created.getOrganizationId(), userId, OrganizationMember.OWNER);
                return created;
            });
    }

    /**
     * Ends a membership and removes the user's memberships of this organization's projects only, in one transaction. A
     * project owner must hand the project over first; the last owner cannot go (OR4).
     */
    private OrganizationMemberResponse end(String organizationId, OrganizationMember target, String status) {
        if (OrganizationMember.OWNER.equals(target.getOrgRole()) && lastOwner(organizationId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "The last owner cannot leave; make another member owner first.");
        }
        List<Project> organizationProjects = projects.findAllByOrganizationIdAndDeletedYnOrderByCreatedAtDesc(organizationId, NOT_DELETED);
        if (organizationProjects.stream().anyMatch(project -> target.getUserId().equals(project.getOwnerId()))) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "User " + target.getUserId() + " owns a project of this organization; transfer its ownership first.");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        target.setMemberStatus(status);
        target.setLeftAt(now);
        members.save(target);
        Set<String> projectIds = organizationProjects.stream().map(Project::getProjectId).collect(Collectors.toSet());
        if (!projectIds.isEmpty()) {
            for (ProjectMember membership : projectMembers.findAllByProjectIdInAndUserIdAndMemberStatus(projectIds, target.getUserId(), ACTIVE)) {
                membership.setMemberStatus("removed");
                projectMembers.save(membership);
            }
        }
        return response(target);
    }

    private Organization organization(String name, String type, String creator) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        Organization organization = new Organization();
        organization.setOrganizationId(ids.generate());
        organization.setOrganizationName(name);
        organization.setOrganizationType(type);
        organization.setOwnerUserId(creator);
        organization.setCreatedAt(now);
        organization.setUpdatedAt(now);
        organization.setDeletedYn(NOT_DELETED);
        return organizations.saveAndFlush(organization);
    }

    private OrganizationMember member(String organizationId, String userId, String role) {
        OrganizationMember member = new OrganizationMember();
        member.setOrganizationMemberId(ids.generate());
        member.setOrganizationId(organizationId);
        member.setUserId(userId);
        member.setOrgRole(role);
        member.setMemberStatus(ACTIVE);
        member.setJoinedAt(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
        return members.saveAndFlush(member);
    }

    private boolean lastOwner(String organizationId) {
        return members.countByOrganizationIdAndOrgRoleAndMemberStatus(organizationId, OrganizationMember.OWNER, ACTIVE) <= 1;
    }

    private Organization live(String organizationId) {
        return organizations.findByOrganizationIdAndDeletedYn(organizationId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private Organization locked(String organizationId) {
        return organizations.findForUpdate(organizationId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private OrganizationMember requireMember(Organization organization) {
        return members.findByOrganizationIdAndUserIdAndMemberStatus(organization.getOrganizationId(), access.requireCurrentUserId(), ACTIVE)
            .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "You are not a member of this organization."));
    }

    private static OrganizationMember requireManager(OrganizationMember member) {
        if (OrganizationMember.MEMBER.equals(member.getOrgRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Only organization owners and admins can do this.");
        }
        return member;
    }

    private OrganizationMember activeMember(String organizationId, String organizationMemberId) {
        return members.findByOrganizationMemberIdAndOrganizationId(organizationMemberId, organizationId)
            .filter(member -> ACTIVE.equals(member.getMemberStatus()))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private static String role(String value) {
        String role = value == null ? OrganizationMember.MEMBER : value.trim().toLowerCase(Locale.ROOT);
        if (!ROLES.contains(role)) throw new BusinessException(ErrorCode.BAD_REQUEST, "orgRole is owner, admin or member.");
        return role;
    }

    private static OrganizationResponse response(Organization organization, String myRole) {
        return new OrganizationResponse(organization.getOrganizationId(), organization.getOrganizationName(),
            organization.getOrganizationType(), organization.getOwnerUserId(), myRole);
    }

    private static OrganizationMemberResponse response(OrganizationMember member) {
        return new OrganizationMemberResponse(member.getOrganizationMemberId(), member.getUserId(), member.getOrgRole(),
            member.getMemberStatus(), member.getJoinedAt(), member.getLeftAt());
    }
}
