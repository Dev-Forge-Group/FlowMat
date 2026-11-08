package org.myweb.flowmat.domain.project.repository;

import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.project.domain.entity.ProjectMember;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, String> {

    boolean existsByProjectIdAndUserIdAndMemberStatus(String projectId, String userId, String memberStatus);

    List<ProjectMember> findAllByUserIdAndMemberStatus(String userId, String memberStatus);

    List<ProjectMember> findAllByProjectIdInAndUserIdAndMemberStatus(java.util.Collection<String> projectIds, String userId,
                                                                     String memberStatus);

    Optional<ProjectMember> findByProjectIdAndUserIdAndMemberStatus(String projectId, String userId, String memberStatus);

    List<ProjectMember> findAllByProjectIdAndMemberStatusOrderByCreatedAtAsc(String projectId, String memberStatus);

    Optional<ProjectMember> findByProjectMemberIdAndMemberStatus(String projectMemberId, String memberStatus);
}
