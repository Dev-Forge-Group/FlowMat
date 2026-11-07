package org.myweb.flowmat.domain.production.repository;

import java.util.Collection;
import java.util.List;
import org.myweb.flowmat.domain.production.domain.entity.WorkInstructionStep;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkInstructionStepRepository extends JpaRepository<WorkInstructionStep, String> {

    List<WorkInstructionStep> findAllByInstructionIdOrderByStepNoAsc(String instructionId);

    List<WorkInstructionStep> findAllByInstructionIdInOrderByStepNoAsc(Collection<String> instructionIds);
}
