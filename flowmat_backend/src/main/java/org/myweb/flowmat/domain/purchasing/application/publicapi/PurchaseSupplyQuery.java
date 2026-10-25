package org.myweb.flowmat.domain.purchasing.application.publicapi;

import java.math.BigDecimal;
import java.util.Map;

/** What is ordered from suppliers and not received yet, for supply planning (docs/domain/purchasing.md PO8). */
public interface PurchaseSupplyQuery {

    /**
     * Per item, in its stock unit: what is left to receive on the project's ordered purchase orders. Drafts and received,
     * closed or cancelled orders do not count. No access check; callers check.
     */
    Map<String, BigDecimal> onOrder(String projectId);
}
