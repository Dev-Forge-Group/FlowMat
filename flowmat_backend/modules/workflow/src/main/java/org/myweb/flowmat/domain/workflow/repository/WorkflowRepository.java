package org.myweb.flowmat.domain.workflow.repository;

import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.workflow.domain.entity.Workflow;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowRepository extends JpaRepository<Workflow, String> {

    List<Workflow> findAllByProjectIdAndDeletedYnOrderByCreatedAtAsc(String projectId, String deletedYn);

    Optional<Workflow> findByWorkflowIdAndDeletedYn(String workflowId, String deletedYn);
}
