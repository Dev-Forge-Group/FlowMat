package org.myweb.flowmat.domain.workflow.repository;

import java.util.Collection;
import java.util.List;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessRequirement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessRequirementRepository extends JpaRepository<ProcessRequirement, String> {

    List<ProcessRequirement> findAllByProcessIdOrderBySortOrderAsc(String processId);

    List<ProcessRequirement> findAllByProcessIdInOrderByProcessIdAscSortOrderAsc(Collection<String> processIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ProcessRequirement r where r.processId = :processId")
    void deleteAllOfProcess(@Param("processId") String processId);
}
