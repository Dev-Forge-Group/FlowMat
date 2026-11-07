package org.myweb.flowmat.domain.production.repository;

import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkOrderRepository extends JpaRepository<WorkOrder, String> {

    /** Serializes order changes, run starts and reservation mutations before their state is read. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WorkOrder w where w.workOrderId = :id and w.deletedYn = 'N'")
    Optional<WorkOrder> findForUpdate(@Param("id") String workOrderId);

    List<WorkOrder> findAllByProjectIdAndDeletedYnOrderByCreatedAtDesc(String projectId, String deletedYn);

    Optional<WorkOrder> findByWorkOrderIdAndDeletedYn(String workOrderId, String deletedYn);

    boolean existsByWorkOrderNumber(String workOrderNumber);

    List<WorkOrder> findAllByEquipmentIdAndDeletedYn(String equipmentId, String deletedYn);

    List<WorkOrder> findAllByProjectIdInAndDeletedYnAndPlannedEndAtLessThanEqual(Collection<String> projectIds, String deletedYn,
                                                                                OffsetDateTime until);
}
