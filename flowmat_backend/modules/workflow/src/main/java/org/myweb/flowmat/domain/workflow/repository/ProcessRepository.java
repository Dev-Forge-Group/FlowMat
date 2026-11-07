package org.myweb.flowmat.domain.workflow.repository;

import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.workflow.domain.entity.Process;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessRepository extends JpaRepository<Process, String> {

    List<Process> findAllByWorkflowIdAndDeletedYnOrderByCreatedAtAsc(String workflowId, String deletedYn);

    Optional<Process> findByProcessIdAndDeletedYn(String processId, String deletedYn);
}
