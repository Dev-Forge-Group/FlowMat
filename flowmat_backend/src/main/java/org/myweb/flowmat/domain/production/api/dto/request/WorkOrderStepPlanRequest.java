package org.myweb.flowmat.domain.production.api.dto.request;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.List;
import org.myweb.flowmat.global.util.WholeNumberDeserializer;

/**
 * A work order's plan by step, sent whole (docs/domain/worker-calendar.md LB23); an empty list clears it.
 *
 * @param steps the workflow's processes in the order to show them, each with its planned worker minutes
 */
public record WorkOrderStepPlanRequest(List<Step> steps) {

    /** @param plannedMinutes whole worker minutes from 1 to 100000, for everyone on the process together */
    public record Step(String nodeId, @JsonDeserialize(using = WholeNumberDeserializer.class) Integer plannedMinutes) {
    }
}
