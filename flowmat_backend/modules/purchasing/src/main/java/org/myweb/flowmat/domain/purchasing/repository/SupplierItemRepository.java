package org.myweb.flowmat.domain.purchasing.repository;

import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.purchasing.domain.entity.SupplierItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierItemRepository extends JpaRepository<SupplierItem, String> {
    List<SupplierItem> findAllByProjectId(String projectId);

    Optional<SupplierItem> findByProjectIdAndItemId(String projectId, String itemId);
}
