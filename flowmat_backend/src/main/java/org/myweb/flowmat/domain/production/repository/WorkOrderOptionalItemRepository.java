package org.myweb.flowmat.domain.production.repository;

import java.util.Collection;
import java.util.List;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrderOptionalItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkOrderOptionalItemRepository extends JpaRepository<WorkOrderOptionalItem, String> {
    List<WorkOrderOptionalItem> findAllByWorkOrderIdInOrderByItemIdAsc(Collection<String> workOrderIds);

    void deleteAllByWorkOrderId(String workOrderId);
}
