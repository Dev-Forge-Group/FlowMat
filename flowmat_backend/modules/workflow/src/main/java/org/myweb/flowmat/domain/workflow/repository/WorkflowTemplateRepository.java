package org.myweb.flowmat.domain.workflow.repository;

import java.util.List;
import org.myweb.flowmat.domain.workflow.domain.entity.WorkflowTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowTemplateRepository extends JpaRepository<WorkflowTemplate, String> {

    List<WorkflowTemplate> findAllByOrderBySortOrderAscCreatedAtAsc();

    List<WorkflowTemplate> findAllByPublicYnOrderBySortOrderAscCreatedAtAsc(String publicYn);
}
