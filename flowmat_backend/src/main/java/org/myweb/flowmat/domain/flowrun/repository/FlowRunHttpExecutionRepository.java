package org.myweb.flowmat.domain.flowrun.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunHttpExecution;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FlowRunHttpExecutionRepository extends JpaRepository<FlowRunHttpExecution, String> {
    Optional<FlowRunHttpExecution> findByStepIdAndRequestId(String stepId, String requestId);
    Optional<FlowRunHttpExecution> findByAttemptId(String attemptId);
    List<FlowRunHttpExecution> findAllByStepIdOrderByStartedAtAscExecutionIdAsc(String stepId);
    @Query("select count(e) > 0 from FlowRunHttpExecution e where e.stepId = :stepId and "
        + "(e.transportStatus in ('PENDING', 'OUTCOME_UNKNOWN') or "
        + "(e.transportStatus in ('SUCCEEDED', 'HTTP_REJECTED', 'RESPONSE_REJECTED') and e.applyStatus <> 'APPLIED'))")
    boolean hasUnresolvedTransmission(@Param("stepId") String stepId);

    /** Metadata only: reviewing an unresolved transmission never loads its request or selected output. */
    @Query("select e.executionId as executionId, r.flowRunId as flowRunId, r.workflowId as workflowId, "
        + "r.status as runStatus, s.stepId as stepId, s.nodeId as nodeId, s.status as stepStatus, "
        + "e.attemptId as attemptId, e.operationId as operationId, e.transportStatus as transportStatus, "
        + "e.applyStatus as applyStatus, e.httpStatus as httpStatus, e.errorCode as errorCode, "
        + "e.startedAt as startedAt, e.completedAt as completedAt "
        + "from FlowRunHttpExecution e join FlowRun r on r.flowRunId = e.flowRunId "
        + "join FlowRunStep s on s.stepId = e.stepId and s.flowRunId = r.flowRunId "
        + "where r.projectId = :projectId "
        + "and (e.transportStatus in ('PENDING', 'OUTCOME_UNKNOWN') or e.applyStatus <> 'APPLIED') "
        + "and (:state = 'ALL' or (:state in ('PENDING', 'OUTCOME_UNKNOWN') and e.transportStatus = :state) "
        + "or (:state in ('APPLY_FAILED', 'LATE') and e.applyStatus = :state)) "
        + "and (:hasCursor = false or e.startedAt < :cursorStartedAt "
        + "or (e.startedAt = :cursorStartedAt and e.executionId < :cursorExecutionId)) "
        + "order by e.startedAt desc, e.executionId desc")
    List<AttentionRow> findAttention(@Param("projectId") String projectId, @Param("state") String state,
        @Param("hasCursor") boolean hasCursor, @Param("cursorStartedAt") OffsetDateTime cursorStartedAt,
        @Param("cursorExecutionId") String cursorExecutionId, Pageable limit);

    interface AttentionRow {
        String getExecutionId();
        String getFlowRunId();
        String getWorkflowId();
        String getRunStatus();
        String getStepId();
        String getNodeId();
        String getStepStatus();
        String getAttemptId();
        String getOperationId();
        String getTransportStatus();
        String getApplyStatus();
        Integer getHttpStatus();
        String getErrorCode();
        OffsetDateTime getStartedAt();
        OffsetDateTime getCompletedAt();
    }
}
