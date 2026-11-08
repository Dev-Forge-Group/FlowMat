package org.myweb.flowmat.domain.purchasing.repository;

import java.util.Collection;
import java.util.List;
import org.myweb.flowmat.domain.purchasing.domain.entity.PurchaseCreditChange;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseCreditChangeRepository extends JpaRepository<PurchaseCreditChange, String> {
    List<PurchaseCreditChange> findAllByReturnIdOrderByChangedAtAscChangeIdAsc(String returnId);

    List<PurchaseCreditChange> findAllByPurchaseOrderIdIn(Collection<String> purchaseOrderIds);
}
