package org.myweb.flowmat.domain.workflow.repository;

import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.workflow.domain.entity.WorkflowRevision;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowRevisionRepository extends JpaRepository<WorkflowRevision, String> {

    List<WorkflowRevision> findAllByWorkflowIdOrderByRevisionNoDesc(String workflowId);

    Optional<WorkflowRevision> findTopByWorkflowIdOrderByRevisionNoDesc(String workflowId);

    Optional<WorkflowRevision> findByWorkflowRevisionIdAndWorkflowId(String revisionId, String workflowId);

    Optional<WorkflowRevision> findTopByWorkflowIdAndStatusOrderByRevisionNoDesc(String workflowId, String status);
}
