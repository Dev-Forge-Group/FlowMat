package org.myweb.flowmat.domain.flowrun.api.dto.response;

import java.util.List;

public record FlowRunStepLineageResponse(
    FlowRunStepResponse step,
    List<FlowRunStepResponse> ancestors,
    List<FlowRunStepResponse> descendants
) {
}
