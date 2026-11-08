package org.myweb.flowmat.domain.production.api.dto.request;

import java.math.BigDecimal;
import java.util.List;

/**
 * What to allocate to a work order. Without lines, the order's BOM for what is still to make (less what its open runs
 * already used); with lines, those quantities in each item's own unit.
 */
public record StockAllocationRequest(List<Line> lines) {

    public record Line(String itemId, BigDecimal quantity) {
    }
}
