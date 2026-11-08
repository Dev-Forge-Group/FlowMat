package org.myweb.flowmat.domain.workflow.application;

import java.util.List;
import org.myweb.flowmat.domain.workflow.api.dto.request.WorkflowCreateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.request.WorkflowUpdateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.response.WorkflowResponse;

public interface WorkflowService {

    List<WorkflowResponse> listWorkflows(String projectId);

    WorkflowResponse createWorkflow(WorkflowCreateRequest request);

    WorkflowResponse getWorkflow(String workflowId);

    WorkflowResponse updateWorkflow(String workflowId, WorkflowUpdateRequest request);

    void deleteWorkflow(String workflowId);
}
