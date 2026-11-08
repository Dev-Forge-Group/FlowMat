package org.myweb.flowmat.domain.workflow.annotation.api.dto.request;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CanvasAnnotationCreateRequest(
    @NotBlank String annotationType,
    String shapeKind,
    @NotNull Double posX,
    @NotNull Double posY,
    Double width,
    Double height,
    Double rotation,
    JsonNode points,
    String textContent,
    JsonNode style,
    String zIndex,
    String groupId
) {
}
