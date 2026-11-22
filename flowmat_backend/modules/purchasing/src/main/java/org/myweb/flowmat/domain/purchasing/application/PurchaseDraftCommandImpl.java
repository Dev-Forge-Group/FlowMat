package org.myweb.flowmat.domain.purchasing.application;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.purchasing.api.dto.request.PurchaseOrderRequest;
import org.myweb.flowmat.domain.purchasing.application.publicapi.PurchaseDraftCommand;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PurchaseDraftCommandImpl implements PurchaseDraftCommand {

    private final PurchaseOrderService orders;

    @Override
    public String draft(String projectId, String supplierName, String note, List<Line> lines) {
        return orders.create(new PurchaseOrderRequest(projectId, supplierName, null, note,
            lines.stream().map(line -> new PurchaseOrderRequest.Line(line.itemId(), line.quantity(), null, null, null)).toList(),
            null)).purchaseOrderId();
    }
}
