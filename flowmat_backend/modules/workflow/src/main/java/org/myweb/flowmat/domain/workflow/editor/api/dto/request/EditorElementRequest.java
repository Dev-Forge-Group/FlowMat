package org.myweb.flowmat.domain.workflow.editor.api.dto.request;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record EditorElementRequest(
    @NotBlank String id,
    @NotBlank String type,
    @NotNull Double x,
    @NotNull Double y,
    @NotNull Double width,
    @NotNull Double height,
    Double rotation,
    Double opacity,
    String parentId,
    Boolean locked,
    Boolean hidden,
    Integer order,
    JsonNode geometry,
    JsonNode style
) {
}
