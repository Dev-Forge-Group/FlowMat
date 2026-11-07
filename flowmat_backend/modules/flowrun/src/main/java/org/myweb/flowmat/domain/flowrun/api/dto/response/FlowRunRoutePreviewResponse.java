package org.myweb.flowmat.domain.flowrun.api.dto.response;

public record FlowRunRoutePreviewResponse(
    String connectionId,
    String targetNodeId,
    boolean willRoute
) {
}
