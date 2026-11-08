package org.myweb.flowmat.domain.production.repository;

import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.production.domain.entity.RunInstructionCheck;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RunInstructionCheckRepository extends JpaRepository<RunInstructionCheck, String> {

    List<RunInstructionCheck> findAllByProductionRunId(String productionRunId);

    /** Confirmations still standing. */
    List<RunInstructionCheck> findAllByProductionRunIdAndUndoneAtIsNull(String productionRunId);

    /** A step's standing confirmation; undone ones are history. */
    Optional<RunInstructionCheck> findByProductionRunIdAndStepIdAndUndoneAtIsNull(String productionRunId, String stepId);

    /** Undone confirmations, the earliest undo first (docs/domain/work-instruction.md R6). */
    List<RunInstructionCheck> findAllByProductionRunIdAndUndoneAtIsNotNullOrderByUndoneAtAsc(String productionRunId);
}
