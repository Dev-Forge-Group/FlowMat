package org.myweb.flowmat.domain.workflow.editor.api.dto.response;

import com.fasterxml.jackson.databind.JsonNode;

public record EditorElementResponse(
    String id,
    String type,
    double x,
    double y,
    double width,
    double height,
    double rotation,
    double opacity,
    String parentId,
    boolean locked,
    boolean hidden,
    int order,
    JsonNode geometry,
    JsonNode style,
    int version,
    long versionNonce
) {
}
