package org.myweb.flowmat.domain.purchasing.repository;

import java.util.Collection;
import java.util.List;
import org.myweb.flowmat.domain.purchasing.domain.entity.PurchaseOrderDateChange;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseOrderDateChangeRepository extends JpaRepository<PurchaseOrderDateChange, String> {
    List<PurchaseOrderDateChange> findAllByPurchaseOrderIdIn(Collection<String> purchaseOrderIds);
}
