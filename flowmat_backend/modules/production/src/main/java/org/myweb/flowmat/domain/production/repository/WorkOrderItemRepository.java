package org.myweb.flowmat.domain.production.repository;

import org.myweb.flowmat.domain.production.domain.entity.WorkOrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkOrderItemRepository extends JpaRepository<WorkOrderItem, String> {
}
