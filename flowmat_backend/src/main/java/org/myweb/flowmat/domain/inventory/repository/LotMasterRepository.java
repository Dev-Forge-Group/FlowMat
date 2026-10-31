package org.myweb.flowmat.domain.inventory.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.inventory.domain.entity.LotMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LotMasterRepository extends JpaRepository<LotMaster, String> {

    boolean existsByProjectIdAndLotNoIgnoreCase(String projectId, String lotNo);

    List<LotMaster> findAllByProjectIdOrderByCreatedAtDesc(String projectId);

    List<LotMaster> findAllByProjectIdAndItemIdOrderByCreatedAtDesc(String projectId, String itemId);

    /** Stock writers share this transaction lock; a lifecycle change must exclude every one of them. */
    @Query(value = "select pg_try_advisory_xact_lock_shared(hashtext('flowmat-lot-lifecycle'), hashtext(:lotId))", nativeQuery = true)
    boolean tryStockLifecycleLock(@Param("lotId") String lotId);

    /** Try rather than wait: callers may already hold stock rows that the lifecycle change needs. */
    @Query(value = "select pg_try_advisory_xact_lock(hashtext('flowmat-lot-lifecycle'), hashtext(:lotId))", nativeQuery = true)
    boolean tryExclusiveLifecycleLock(@Param("lotId") String lotId);

    /** Locks the LOT row until the transaction ends, so two re-checks of its status take turns (LotStatusResync). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from LotMaster l where l.lotId = :lotId")
    Optional<LotMaster> findForUpdate(@Param("lotId") String lotId);
}
