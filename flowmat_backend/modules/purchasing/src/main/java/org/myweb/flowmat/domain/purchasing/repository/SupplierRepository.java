package org.myweb.flowmat.domain.purchasing.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.purchasing.domain.entity.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierRepository extends JpaRepository<Supplier, String> {
    List<Supplier> findAllByProjectId(String projectId);

    boolean existsByProjectId(String projectId);

    /** The project's supplier with this name, ignoring case as the unique index does. */
    @Query("select s from Supplier s where s.projectId = :projectId and lower(s.supplierName) = lower(:name)")
    Optional<Supplier> findByName(@Param("projectId") String projectId, @Param("name") String name);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Supplier s where s.supplierId = :id")
    Optional<Supplier> findForUpdate(@Param("id") String supplierId);

    /** Holds a lock on a key until the transaction ends; the names of a project's suppliers change one at a time. */
    @Query(value = "select 1 from pg_advisory_xact_lock(hashtext(:key))", nativeQuery = true)
    Integer lockKey(@Param("key") String key);
}
