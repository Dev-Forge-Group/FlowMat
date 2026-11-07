package org.myweb.flowmat.domain.inventory.repository;

import java.util.Collection;
import java.util.List;
import org.myweb.flowmat.domain.inventory.domain.entity.InventoryCountPlanLine;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryCountPlanLineRepository extends JpaRepository<InventoryCountPlanLine, String> {
    List<InventoryCountPlanLine> findAllByPlanIdOrderByInventoryIdAsc(String planId);
    List<InventoryCountPlanLine> findAllByPlanIdInOrderByInventoryIdAsc(Collection<String> planIds);
}
