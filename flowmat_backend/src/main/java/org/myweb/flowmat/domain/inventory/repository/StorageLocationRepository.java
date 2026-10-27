package org.myweb.flowmat.domain.inventory.repository;

import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.inventory.domain.entity.StorageLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StorageLocationRepository extends JpaRepository<StorageLocation, String> {

    List<StorageLocation> findAllByProjectIdAndDeletedYn(String projectId, String deletedYn);

    /** Scalar fields avoid reusing a location loaded before a stock movement waited. */
    @Query("""
        select l.locationId as locationId, l.locationCode as locationCode,
               l.parentLocationId as parentLocationId, l.storageCondition as storageCondition
        from StorageLocation l where l.projectId = :projectId and l.deletedYn = :deletedYn
        """)
    List<StorageConditionFields> findStorageConditions(@Param("projectId") String projectId, @Param("deletedYn") String deletedYn);

    interface StorageConditionFields {
        String getLocationId();
        String getLocationCode();
        String getParentLocationId();
        String getStorageCondition();
    }

    Optional<StorageLocation> findByLocationIdAndDeletedYn(String locationId, String deletedYn);

    /** Resolve the lock scope without caching location state before waiting for another mutation. */
    @Query("select l.projectId from StorageLocation l where l.locationId = :id and l.deletedYn = 'N'")
    Optional<String> findLiveProjectId(@Param("id") String locationId);

    List<StorageLocation> findAllByParentLocationIdAndDeletedYn(String parentLocationId, String deletedYn);

    boolean existsByParentLocationIdAndDeletedYn(String parentLocationId, String deletedYn);

    @Query("""
        select l from StorageLocation l
        where l.projectId = :projectId and l.deletedYn = 'N' and lower(l.locationCode) = lower(:code)
        """)
    Optional<StorageLocation> findLiveByCode(@Param("projectId") String projectId, @Param("code") String code);
}
