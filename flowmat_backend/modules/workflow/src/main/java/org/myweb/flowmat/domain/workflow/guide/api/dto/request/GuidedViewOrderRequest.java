package org.myweb.flowmat.domain.workflow.guide.api.dto.request;

import java.util.List;

/** Every chapter of the workflow, by id, in the new order. */
public record GuidedViewOrderRequest(List<String> guidedViewIds) {
}
