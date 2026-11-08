package org.myweb.flowmat.domain.inventory.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.inventory.domain.entity.WarehouseTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WarehouseTaskRepository extends JpaRepository<WarehouseTask, String> {

    List<WarehouseTask> findAllByProjectIdOrderByCreatedAtDesc(String projectId);

    List<WarehouseTask> findAllByProjectIdAndStatus(String projectId, String status);

    long countByProjectId(String projectId);

    /** Resolve the lock scope without loading a task's potentially changing places or status. */
    @Query("select t.projectId from WarehouseTask t where t.taskId = :id")
    Optional<String> findProjectIdByTaskId(@Param("id") String taskId);

    /** Locks the task so two people doing it at once cannot both move the stock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from WarehouseTask t where t.taskId = :id")
    Optional<WarehouseTask> findForUpdate(@Param("id") String taskId);

    /** Holds a lock on a key until the transaction ends; planning tasks in a project takes turns. */
    @Query(value = "select 1 from pg_advisory_xact_lock(hashtext(:key))", nativeQuery = true)
    Integer lockKey(@Param("key") String key);

    /**
     * Planning, task mutations and location mutations take this lock before place, inventory or task row locks.
     * A rename therefore sees committed state and cannot deadlock with a task moving between its places.
     */
    default void lockProject(String projectId) {
        lockKey("warehouse-task|" + projectId);
    }

    /**
     * Points the project's tasks at a renamed place (docs/domain/storage-location.md L7): open ones so doing them still
     * finds a listed place, done ones so they name the same place as the stock records do.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
        update warehouse_task
           set to_location = case when lower(trim(to_location)) = lower(:previous) then :code else to_location end,
               from_location = case when lower(trim(from_location)) = lower(:previous) then :code else from_location end
         where project_id = :projectId
           and (lower(trim(to_location)) = lower(:previous) or lower(trim(from_location)) = lower(:previous))
        """, nativeQuery = true)
    int renamePlace(@Param("projectId") String projectId, @Param("previous") String previous, @Param("code") String code);
}
