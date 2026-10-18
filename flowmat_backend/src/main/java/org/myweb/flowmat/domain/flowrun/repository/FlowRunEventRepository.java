package org.myweb.flowmat.domain.flowrun.repository;

import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlowRunEventRepository extends JpaRepository<FlowRunEvent, String> {
    List<FlowRunEvent> findAllByFlowRunIdOrderByOccurredAtAscEventIdAsc(String flowRunId);
    /** The step's latest event of a type. */
    Optional<FlowRunEvent> findFirstByStepIdAndEventTypeOrderByOccurredAtDescEventIdDesc(String stepId, String eventType);
}
