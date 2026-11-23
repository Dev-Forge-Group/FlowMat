package org.myweb.flowmat.domain.production.repository;

import java.util.List;
import org.myweb.flowmat.domain.production.domain.entity.ResourceCapacity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResourceCapacityRepository extends JpaRepository<ResourceCapacity, ResourceCapacity.Key> {

    List<ResourceCapacity> findAllByProjectIdOrderByResourceTypeAscUnitAsc(String projectId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ResourceCapacity capacity where capacity.projectId = :projectId")
    void deleteAllOfProject(@Param("projectId") String projectId);
}
