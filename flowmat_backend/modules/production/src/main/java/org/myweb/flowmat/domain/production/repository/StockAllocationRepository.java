package org.myweb.flowmat.domain.production.repository;

import java.util.Collection;
import java.util.List;
import org.myweb.flowmat.domain.production.domain.entity.StockAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockAllocationRepository extends JpaRepository<StockAllocation, String> {

    List<StockAllocation> findAllByWorkOrderIdOrderByCreatedAtAscAllocationIdAsc(String workOrderId);

    List<StockAllocation> findAllByWorkOrderIdAndStatusOrderByCreatedAtAscAllocationIdAsc(String workOrderId, String status);

    List<StockAllocation> findAllByWorkOrderIdAndInventoryIdAndStatusOrderByCreatedAtAscAllocationIdAsc(
        String workOrderId, String inventoryId, String status);

    List<StockAllocation> findAllByWorkOrderIdInAndStatus(Collection<String> workOrderIds, String status);

    /** Holds a lock on a key until the transaction ends; allocating in a project takes turns. */
    @Query(value = "select 1 from pg_advisory_xact_lock(hashtext(:key))", nativeQuery = true)
    Integer lockKey(@Param("key") String key);
}
