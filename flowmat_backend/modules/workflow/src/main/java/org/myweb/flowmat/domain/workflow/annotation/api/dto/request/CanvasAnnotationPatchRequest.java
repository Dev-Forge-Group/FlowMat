package org.myweb.flowmat.domain.workflow.annotation.api.dto.request;

import com.fasterxml.jackson.databind.JsonNode;

public record CanvasAnnotationPatchRequest(
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
    Integer version,
    Long versionNonce
) {
}
