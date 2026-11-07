package org.myweb.flowmat.domain.project.repository;

import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.project.domain.entity.ProjectInvite;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectInviteRepository extends JpaRepository<ProjectInvite, String> {

    List<ProjectInvite> findAllByProjectIdOrderByCreatedAtDesc(String projectId);

    Optional<ProjectInvite> findByInviteToken(String inviteToken);

    boolean existsByProjectIdAndInvitedEmailIgnoreCaseAndInviteStatus(String projectId, String invitedEmail, String inviteStatus);
}
