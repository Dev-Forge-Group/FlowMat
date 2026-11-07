package org.myweb.flowmat.domain.purchasing.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.purchasing.domain.entity.PurchaseOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, String> {
    List<PurchaseOrder> findAllByProjectIdOrderByCreatedAtDescPurchaseOrderIdDesc(String projectId);

    long countByProjectId(String projectId);

    List<PurchaseOrder> findAllByProjectIdAndStatus(String projectId, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PurchaseOrder o where o.purchaseOrderId = :id")
    Optional<PurchaseOrder> findForUpdate(@Param("id") String purchaseOrderId);

    /** Holds a lock on a key until the transaction ends; new order numbers of a project take turns. */
    @Query(value = "select 1 from pg_advisory_xact_lock(hashtext(:key))", nativeQuery = true)
    Integer lockKey(@Param("key") String key);
}
