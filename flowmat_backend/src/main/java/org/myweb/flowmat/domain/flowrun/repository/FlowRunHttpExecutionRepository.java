package org.myweb.flowmat.domain.flowrun.repository;

import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunHttpExecution;
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
}
