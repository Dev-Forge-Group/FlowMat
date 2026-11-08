package org.myweb.flowmat.domain.flowrun.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStepAttempt;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FlowRunStepAttemptRepository extends JpaRepository<FlowRunStepAttempt, String> {
    List<FlowRunStepAttempt> findAllByStepIdOrderByAttemptNoAsc(String stepId);
    Optional<FlowRunStepAttempt> findTopByStepIdOrderByAttemptNoDesc(String stepId);

    /**
     * Running attempts of running runs past their time limit and not yet marked late, earliest first
     * (docs/domain/flow-run-execution-policy.md EP7, docs/domain/flow-run.md F12).
     */
    @Query("select attempt.attemptId from FlowRunStepAttempt attempt, FlowRunStep step, FlowRun run"
        + " where step.stepId = attempt.stepId and run.flowRunId = step.flowRunId and run.status = 'running'"
        + " and attempt.status = 'running' and attempt.timeoutAt <= :now and attempt.overdueAt is null"
        + " order by attempt.timeoutAt, attempt.attemptId")
    List<String> findTimedOutAttemptIds(@Param("now") OffsetDateTime now, Pageable page);

    @Query("select step.flowRunId from FlowRunStep step, FlowRunStepAttempt attempt"
        + " where step.stepId = attempt.stepId and attempt.attemptId = :attemptId")
    Optional<String> findFlowRunIdByAttemptId(@Param("attemptId") String attemptId);
}
