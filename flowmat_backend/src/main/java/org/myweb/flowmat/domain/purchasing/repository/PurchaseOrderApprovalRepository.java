package org.myweb.flowmat.domain.purchasing.repository;

import java.util.Collection;
import java.util.List;
import org.myweb.flowmat.domain.purchasing.domain.entity.PurchaseOrderApproval;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseOrderApprovalRepository extends JpaRepository<PurchaseOrderApproval, String> {
    List<PurchaseOrderApproval> findAllByPurchaseOrderId(String purchaseOrderId);

    List<PurchaseOrderApproval> findAllByPurchaseOrderIdIn(Collection<String> purchaseOrderIds);
}
