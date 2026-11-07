package org.myweb.flowmat.domain.workflow.collab.dto;

import java.util.List;
import org.myweb.flowmat.domain.workflow.annotation.api.dto.response.CanvasAnnotationResponse;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessConnectionResponse;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessIoResponse;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessResponse;
import org.myweb.flowmat.domain.workflow.api.dto.response.WorkflowResponse;

public record GraphEntityPayload(
    WorkflowResponse workflow,
    ProcessResponse process,
    List<ProcessIoResponse> processIos,
    ProcessConnectionResponse connection,
    CanvasAnnotationResponse annotation
) {
}
