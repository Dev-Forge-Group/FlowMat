package org.myweb.flowmat.domain.production.api.dto.request;

import java.util.List;

/**
 * Work orders to plan together, in the order they should run (docs/domain/equipment-schedule.md P8).
 *
 * @param workOrderIds 1 to 50 orders of the project, each once
 * @param from         where every search starts, with an offset; without it each order starts as a single suggestion does
 */
public record WorkOrderBatchPlanRequest(String projectId, List<String> workOrderIds, String from) {
}
