package org.myweb.flowmat.domain.purchasing.application;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.purchasing.application.publicapi.PurchaseSupplyQuery;
import org.myweb.flowmat.domain.purchasing.domain.entity.PurchaseOrder;
import org.myweb.flowmat.domain.purchasing.domain.entity.PurchaseOrderLine;
import org.myweb.flowmat.domain.purchasing.repository.PurchaseOrderLineRepository;
import org.myweb.flowmat.domain.purchasing.repository.PurchaseOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PurchaseSupplyQueryImpl implements PurchaseSupplyQuery {

    private final PurchaseOrderRepository orders;
    private final PurchaseOrderLineRepository lines;

    @Override
    public Map<String, BigDecimal> onOrder(String projectId) {
        List<String> ordered = orders.findAllByProjectIdAndStatus(projectId, PurchaseOrderService.ORDERED).stream()
            .map(PurchaseOrder::getPurchaseOrderId)
            .toList();
        if (ordered.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, BigDecimal> onOrder = new HashMap<>();
        for (PurchaseOrderLine line : lines.findAllByPurchaseOrderIdInOrderByLineNoAsc(ordered)) {
            if (line.remaining().signum() > 0) {
                onOrder.merge(line.getItemId(), line.remaining(), BigDecimal::add);
            }
        }
        return onOrder;
    }
}
