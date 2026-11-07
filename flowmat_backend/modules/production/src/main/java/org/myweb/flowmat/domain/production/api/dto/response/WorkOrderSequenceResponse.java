package org.myweb.flowmat.domain.production.api.dto.response;

import java.util.List;

/**
 * A suggested order for planning several work orders with the fewest changeovers (docs/domain/equipment-schedule.md
 * P13-P16). Nothing is changed.
 *
 * @param workOrderIds           every requested order: each equipment's orders in the suggested order (equipment in the order
 *                               first seen), then the orders without equipment as given
 * @param changeoverMinutes      the changeover minutes of that order, from the order already planned before on each equipment
 * @param givenChangeoverMinutes the same in the order given
 * @param groups                 one per equipment
 */
public record WorkOrderSequenceResponse(
    List<String> workOrderIds,
    int changeoverMinutes,
    int givenChangeoverMinutes,
    List<Group> groups
) {

    /** @param after the number of the order planned just before on this equipment, whose item the first one changes over from */
    public record Group(String equipmentId, String after, List<String> workOrderIds, int changeoverMinutes,
                        int givenChangeoverMinutes) {
    }
}
