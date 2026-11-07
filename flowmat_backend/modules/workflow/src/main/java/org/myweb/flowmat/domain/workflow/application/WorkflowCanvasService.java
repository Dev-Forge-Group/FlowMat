package org.myweb.flowmat.domain.workflow.application;

import org.myweb.flowmat.domain.workflow.api.dto.response.WorkflowCanvasResponse;

public interface WorkflowCanvasService {

    WorkflowCanvasResponse getCanvas(String workflowId);
}
