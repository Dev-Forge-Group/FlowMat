package org.myweb.flowmat.domain.workflow.editor.repository;

import java.util.List;
import org.myweb.flowmat.domain.workflow.editor.domain.WorkflowEditorElement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowEditorElementRepository extends JpaRepository<WorkflowEditorElement, String> {

    @Query("""
        select element
        from WorkflowEditorElement element
        where element.workflowId = :workflowId
          and element.deletedYn = :deletedYn
        order by element.elementOrder asc, element.createdAt asc
        """)
    List<WorkflowEditorElement> findAllByWorkflowIdAndDeletedYnOrderByElementOrderAscCreatedAtAsc(
        @Param("workflowId") String workflowId,
        @Param("deletedYn") String deletedYn
    );
}
