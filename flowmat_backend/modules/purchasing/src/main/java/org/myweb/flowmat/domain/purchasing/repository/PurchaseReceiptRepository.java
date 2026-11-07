package org.myweb.flowmat.domain.purchasing.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.myweb.flowmat.domain.purchasing.domain.entity.PurchaseReceipt;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseReceiptRepository extends JpaRepository<PurchaseReceipt, String> {
    List<PurchaseReceipt> findAllByPurchaseOrderIdInOrderByReceivedAtAscReceiptIdAsc(Collection<String> purchaseOrderIds);

    List<PurchaseReceipt> findAllByPurchaseOrderIdAndRequestId(String purchaseOrderId, UUID requestId);

    boolean existsByPurchaseOrderId(String purchaseOrderId);
}
