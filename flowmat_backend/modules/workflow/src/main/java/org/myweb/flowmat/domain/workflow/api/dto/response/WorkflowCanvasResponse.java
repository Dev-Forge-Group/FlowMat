package org.myweb.flowmat.domain.workflow.api.dto.response;

import java.util.List;
import org.myweb.flowmat.domain.workflow.annotation.api.dto.response.CanvasAnnotationResponse;

public record WorkflowCanvasResponse(
    WorkflowResponse workflow,
    long graphSeq,
    List<ProcessResponse> processes,
    List<ProcessIoResponse> processIos,
    List<ProcessConnectionResponse> connections,
    List<CanvasAnnotationResponse> annotations,
    String currentUserRole
) {
}
