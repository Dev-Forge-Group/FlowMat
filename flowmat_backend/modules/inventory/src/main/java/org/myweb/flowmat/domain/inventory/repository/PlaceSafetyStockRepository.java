package org.myweb.flowmat.domain.inventory.repository;

import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.inventory.domain.entity.PlaceSafetyStock;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaceSafetyStockRepository extends JpaRepository<PlaceSafetyStock, String> {

    List<PlaceSafetyStock> findAllByProjectId(String projectId);

    Optional<PlaceSafetyStock> findByItemIdAndLocationId(String itemId, String locationId);
}
