package org.myweb.flowmat.domain.project.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.project.domain.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, String> {

    List<Project> findAllByDeletedYnOrderByCreatedAtDesc(String deletedYn);

    List<Project> findAllByOwnerIdAndDeletedYnOrderByCreatedAtDesc(String ownerId, String deletedYn);

    List<Project> findAllByProjectIdInAndDeletedYnOrderByCreatedAtDesc(Collection<String> projectIds, String deletedYn);

    Optional<Project> findByProjectIdAndDeletedYn(String projectId, String deletedYn);

    List<Project> findAllByOrganizationIdAndDeletedYnOrderByCreatedAtDesc(String organizationId, String deletedYn);
}
