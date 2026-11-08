package org.myweb.flowmat.domain.project.repository;

import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.project.domain.entity.OrganizationMember;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationMemberRepository extends JpaRepository<OrganizationMember, String> {
    Optional<OrganizationMember> findByOrganizationIdAndUserIdAndMemberStatus(String organizationId, String userId, String memberStatus);

    Optional<OrganizationMember> findByOrganizationMemberIdAndOrganizationId(String organizationMemberId, String organizationId);

    List<OrganizationMember> findAllByOrganizationIdAndMemberStatusOrderByJoinedAtAscOrganizationMemberIdAsc(
        String organizationId, String memberStatus);

    List<OrganizationMember> findAllByUserIdAndMemberStatus(String userId, String memberStatus);

    boolean existsByOrganizationIdAndUserIdAndMemberStatus(String organizationId, String userId, String memberStatus);

    long countByOrganizationIdAndOrgRoleAndMemberStatus(String organizationId, String orgRole, String memberStatus);
}
