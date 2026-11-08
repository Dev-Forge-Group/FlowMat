package org.myweb.flowmat.domain.inventory.repository;

import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.inventory.domain.entity.StockAlert;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockAlertRepository extends JpaRepository<StockAlert, String> {

    Optional<StockAlert> findFirstByInventoryIdAndAlertTypeAndResolvedYn(String inventoryId, String alertType, String resolvedYn);

    List<StockAlert> findAllByProjectIdAndResolvedYnOrderByTriggeredAtDesc(String projectId, String resolvedYn);

    /** Open and closed, newest first; the history is capped. */
    List<StockAlert> findTop200ByProjectIdOrderByTriggeredAtDesc(String projectId);

    List<StockAlert> findAllByResolvedYn(String resolvedYn);
}
