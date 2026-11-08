package org.myweb.flowmat.domain.inventory.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.myweb.flowmat.domain.inventory.domain.entity.InventoryCountPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryCountPlanRepository extends JpaRepository<InventoryCountPlan, String> {
    Optional<InventoryCountPlan> findByProjectIdAndRequestId(String projectId, UUID requestId);
    List<InventoryCountPlan> findTop50ByProjectIdOrderByCreatedAtDesc(String projectId);
    /** Serialize a create key even when competing requests contain disjoint stock rows. */
    @Query(value = "select 1 from pg_advisory_xact_lock(hashtext(:key))", nativeQuery = true)
    Integer lockRequestKey(@Param("key") String key);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from InventoryCountPlan p where p.planId = :id")
    Optional<InventoryCountPlan> findForUpdate(@Param("id") String planId);
}
