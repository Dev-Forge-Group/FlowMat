package org.myweb.flowmat.domain.production.repository;
import java.util.List;
import org.myweb.flowmat.domain.production.domain.entity.InstructionAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
public interface InstructionAttachmentRepository extends JpaRepository<InstructionAttachment,String> {
    List<InstructionAttachment> findAllByInstructionIdAndDeletedYnOrderByCreatedAtAscAttachmentIdAsc(String instructionId,String deletedYn);
}
