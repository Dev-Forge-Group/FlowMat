package org.myweb.flowmat.domain.workflow.application;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowProductionQuery;
import org.myweb.flowmat.domain.workflow.domain.entity.WorkflowRevision;
import org.myweb.flowmat.domain.workflow.repository.WorkflowRevisionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkflowProductionQueryImpl implements WorkflowProductionQuery {
    private final WorkflowRevisionRepository revisions;
    public Optional<Revision> findRevision(String id,String workflowId) { return revisions.findByWorkflowRevisionIdAndWorkflowId(id,workflowId).map(this::revision); }
    public Optional<Revision> latestPublishedRevision(String workflowId) { return revisions.findTopByWorkflowIdAndStatusOrderByRevisionNoDesc(workflowId,"published").map(this::revision); }
    private Revision revision(WorkflowRevision row) { return new Revision(row.getWorkflowRevisionId(),row.getStatus(),row.getSnapshotJson(),row.getRevisionNo()); }
}
