package org.myweb.flowmat.domain.workflow.collab.dto;

import org.myweb.flowmat.domain.workflow.annotation.api.dto.response.CanvasAnnotationResponse;

public record GraphAnnotationPayload(
    CanvasAnnotationResponse annotation
) {
}
