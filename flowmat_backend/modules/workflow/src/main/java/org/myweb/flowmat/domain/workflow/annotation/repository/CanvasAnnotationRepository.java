package org.myweb.flowmat.domain.workflow.annotation.repository;

import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.workflow.annotation.domain.CanvasAnnotation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CanvasAnnotationRepository extends JpaRepository<CanvasAnnotation, String> {

    @Query("""
        select annotation
        from CanvasAnnotation annotation
        where annotation.workflowId = :workflowId
          and annotation.deletedYn = :deletedYn
        order by annotation.zIndex asc, annotation.createdAt asc
        """)
    List<CanvasAnnotation> findAllByWorkflowIdAndDeletedYnOrderByZIndexAscCreatedAtAsc(
        @Param("workflowId") String workflowId,
        @Param("deletedYn") String deletedYn
    );

    @Query("""
        select annotation
        from CanvasAnnotation annotation
        where annotation.annotationId = :annotationId
          and annotation.deletedYn = :deletedYn
        """)
    Optional<CanvasAnnotation> findByAnnotationIdAndDeletedYn(
        @Param("annotationId") String annotationId,
        @Param("deletedYn") String deletedYn
    );
}
