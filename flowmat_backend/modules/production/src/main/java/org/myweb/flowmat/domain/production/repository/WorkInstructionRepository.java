package org.myweb.flowmat.domain.production.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.production.domain.entity.WorkInstruction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkInstructionRepository extends JpaRepository<WorkInstruction, String> {

    /** Locks editing, releasing and revision copying so they validate the committed status of the same instruction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from WorkInstruction i where i.instructionId = :id and i.deletedYn = 'N'")
    Optional<WorkInstruction> findForUpdate(@Param("id") String instructionId);

    List<WorkInstruction> findAllByProjectIdAndDeletedYnOrderByItemIdAscRevisionNoDesc(String projectId, String deletedYn);

    List<WorkInstruction> findAllByItemIdAndDeletedYnOrderByRevisionNoDesc(String itemId, String deletedYn);

    Optional<WorkInstruction> findByInstructionIdAndDeletedYn(String instructionId, String deletedYn);

    Optional<WorkInstruction> findFirstByItemIdAndStatusAndDeletedYn(String itemId, String status, String deletedYn);
}
