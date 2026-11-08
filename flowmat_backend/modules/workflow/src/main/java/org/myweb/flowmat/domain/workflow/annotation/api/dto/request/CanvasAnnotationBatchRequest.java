package org.myweb.flowmat.domain.workflow.annotation.api.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record CanvasAnnotationBatchRequest(
    @NotEmpty List<@Valid CanvasAnnotationBatchItemRequest> items
) {
    public record CanvasAnnotationBatchItemRequest(
        String annotationId,
        Double posX,
        Double posY,
        Double width,
        Double height,
        Double rotation,
        String zIndex,
        String groupId
    ) {
    }
}
