package org.myweb.flowmat.domain.workflow.annotation.api.dto.response;

import com.fasterxml.jackson.databind.JsonNode;

public record CanvasAnnotationResponse(
    String annotationId,
    String workflowId,
    String projectId,
    String annotationType,
    String shapeKind,
    Double posX,
    Double posY,
    Double width,
    Double height,
    Double rotation,
    JsonNode points,
    String textContent,
    JsonNode style,
    String zIndex,
    String groupId,
    String lockedYn,
    int version,
    long versionNonce
) {
}
