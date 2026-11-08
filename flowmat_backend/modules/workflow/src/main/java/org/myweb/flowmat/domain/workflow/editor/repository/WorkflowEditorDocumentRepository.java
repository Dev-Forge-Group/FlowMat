package org.myweb.flowmat.domain.workflow.editor.repository;

import org.myweb.flowmat.domain.workflow.editor.domain.WorkflowEditorDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface WorkflowEditorDocumentRepository extends JpaRepository<WorkflowEditorDocument, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select document from WorkflowEditorDocument document where document.workflowId = :workflowId")
    Optional<WorkflowEditorDocument> findByWorkflowIdForUpdate(@Param("workflowId") String workflowId);
}
