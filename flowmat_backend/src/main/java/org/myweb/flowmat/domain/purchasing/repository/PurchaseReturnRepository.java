package org.myweb.flowmat.domain.purchasing.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.myweb.flowmat.domain.purchasing.domain.entity.PurchaseReturn;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseReturnRepository extends JpaRepository<PurchaseReturn, String> {
    List<PurchaseReturn> findAllByPurchaseOrderIdIn(Collection<String> purchaseOrderIds);

    List<PurchaseReturn> findAllByLineId(String lineId);

    Optional<PurchaseReturn> findByPurchaseOrderIdAndRequestId(String purchaseOrderId, UUID requestId);
}
