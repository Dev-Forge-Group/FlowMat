package org.myweb.flowmat.domain.inventory.api.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * The picks planned now and, per item, how the need was covered (docs/domain/warehouse-task.md).
 *
 * @param tasks the pick tasks made by this request
 */
public record PickListResponse(List<WarehouseTaskResponse> tasks, List<Line> lines) {

    /**
     * All in the item's stock unit.
     *
     * @param atStaging usable stock already at the staging place
     * @param alreadyPlanned open picks to the staging place made before
     * @param plannedNow picks made by this request
     * @param shortage what could not be planned: not enough free stock elsewhere
     */
    public record Line(
        String itemId,
        String itemCode,
        BigDecimal required,
        BigDecimal atStaging,
        BigDecimal alreadyPlanned,
        BigDecimal plannedNow,
        BigDecimal shortage
    ) {
    }
}
