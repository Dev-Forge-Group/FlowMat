package org.myweb.flowmat.domain.quality.repository;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.quality.domain.entity.Nonconformity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NonconformityRepository extends JpaRepository<Nonconformity, String> {

    List<Nonconformity> findAllByProjectIdOrderByRaisedAtDesc(String projectId);

    long countByProjectId(String projectId);

    List<Nonconformity> findAllByProjectIdInAndStatusAndVerifiedAtIsNullAndVerificationDueDateIsNotNull(
        Collection<String> projectIds, String status);

    /** Locks the nonconformity so its actions are numbered and changed one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from Nonconformity n where n.nonconformityId = :id")
    Optional<Nonconformity> findForUpdate(@Param("id") String nonconformityId);

    /** Holds a lock on a key until the transaction ends; two nonconformities raised at once take turns for a number. */
    @Query(value = "select 1 from pg_advisory_xact_lock(hashtext(:key))", nativeQuery = true)
    Integer lockKey(@Param("key") String key);
}
