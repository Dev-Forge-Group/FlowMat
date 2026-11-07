package org.myweb.flowmat.domain.production.application;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.application.PickWorkOrders;
import org.myweb.flowmat.domain.production.application.publicapi.WorkOrderQuery;
import org.myweb.flowmat.domain.production.application.publicapi.WorkOrderView;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Answers inventory's {@link PickWorkOrders} from the production work order reads. */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryPickWorkOrders implements PickWorkOrders {

    private final WorkOrderQuery workOrderQuery;

    @Override
    public Optional<PickWorkOrder> findProjectWorkOrder(String projectId, String workOrderId) {
        return workOrderQuery.findProjectWorkOrder(projectId, workOrderId).map(InventoryPickWorkOrders::pickWorkOrder);
    }

    @Override
    public Map<String, PickWorkOrder> findWorkOrders(Collection<String> workOrderIds) {
        Map<String, PickWorkOrder> found = new LinkedHashMap<>();
        workOrderQuery.findWorkOrders(workOrderIds).forEach((id, order) -> found.put(id, pickWorkOrder(order)));
        return found;
    }

    private static PickWorkOrder pickWorkOrder(WorkOrderView order) {
        return new PickWorkOrder(order.workOrderId(), order.workOrderNumber(), order.workOrderStatus(), order.acceptsRuns(),
            order.bomId(), order.targetItemId(), order.targetQuantity(), order.plannedStartAt(), order.optionalItemIds());
    }
}
