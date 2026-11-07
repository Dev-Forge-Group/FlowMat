package org.myweb.flowmat.domain.workflow.repository;

import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessConnectionRepository extends JpaRepository<ProcessConnection, String> {

    List<ProcessConnection> findAllByWorkflowIdAndDeletedYnOrderByCreatedAtAsc(String workflowId, String deletedYn);

    Optional<ProcessConnection> findByConnectionIdAndDeletedYn(String connectionId, String deletedYn);

    @Query("select c from ProcessConnection c where c.deletedYn = 'N' and "
        + "(c.fromIoId = :processIoId or c.toIoId = :processIoId)")
    List<ProcessConnection> findLiveConnectionsForPort(@Param("processIoId") String processIoId);
}
