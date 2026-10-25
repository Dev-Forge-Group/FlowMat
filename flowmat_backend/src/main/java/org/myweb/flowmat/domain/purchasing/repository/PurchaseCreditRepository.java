package org.myweb.flowmat.domain.purchasing.repository;

import java.util.Collection;
import java.util.List;
import org.myweb.flowmat.domain.purchasing.domain.entity.PurchaseCredit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseCreditRepository extends JpaRepository<PurchaseCredit, String> {
    List<PurchaseCredit> findAllByPurchaseOrderIdIn(Collection<String> purchaseOrderIds);
}
