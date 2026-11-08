package org.myweb.flowmat.domain.inventory.repository;

import org.myweb.flowmat.domain.inventory.domain.entity.InventorySetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventorySettingRepository extends JpaRepository<InventorySetting, String> {

    /** Holds a lock on a key until the transaction ends; a project's settings change one at a time. */
    @Query(value = "select 1 from pg_advisory_xact_lock(hashtext(:key))", nativeQuery = true)
    Integer lockKey(@Param("key") String key);
}
