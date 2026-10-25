package org.myweb.flowmat.domain.purchasing.repository;

import java.util.Collection;
import java.util.List;
import org.myweb.flowmat.domain.purchasing.domain.entity.PurchaseOrderLine;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseOrderLineRepository extends JpaRepository<PurchaseOrderLine, String> {
    List<PurchaseOrderLine> findAllByPurchaseOrderIdOrderByLineNoAsc(String purchaseOrderId);

    List<PurchaseOrderLine> findAllByPurchaseOrderIdInOrderByLineNoAsc(Collection<String> purchaseOrderIds);
}
