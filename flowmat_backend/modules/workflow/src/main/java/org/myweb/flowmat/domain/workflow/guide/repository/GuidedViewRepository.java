package org.myweb.flowmat.domain.workflow.guide.repository;

import java.util.List;
import org.myweb.flowmat.domain.workflow.guide.domain.GuidedView;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuidedViewRepository extends JpaRepository<GuidedView, String> {

    List<GuidedView> findAllByWorkflowIdOrderBySortOrderAsc(String workflowId);
}
