package org.myweb.flowmat.domain.inventory.repository;

import java.util.Collection;
import java.util.List;
import org.myweb.flowmat.domain.inventory.domain.entity.LotTrace;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LotTraceRepository extends JpaRepository<LotTrace, String> {

    List<LotTrace> findAllByChildLotIdIn(Collection<String> childLotIds);

    List<LotTrace> findAllByParentLotIdIn(Collection<String> parentLotIds);

    boolean existsByParentLotIdAndChildLotIdAndProductionRunId(String parentLotId, String childLotId, String productionRunId);

    void deleteAllByProductionRunId(String productionRunId);
}
