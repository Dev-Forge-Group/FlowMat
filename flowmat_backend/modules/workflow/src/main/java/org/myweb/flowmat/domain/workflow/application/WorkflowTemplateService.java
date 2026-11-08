package org.myweb.flowmat.domain.workflow.application;

import java.util.List;
import org.myweb.flowmat.domain.workflow.api.dto.request.WorkflowTemplateApplyRequest;
import org.myweb.flowmat.domain.workflow.api.dto.request.WorkflowTemplateCreateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.request.WorkflowTemplateUpdateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.response.WorkflowResponse;
import org.myweb.flowmat.domain.workflow.api.dto.response.WorkflowTemplateResponse;

public interface WorkflowTemplateService {

    List<WorkflowTemplateResponse> listTemplates();

    WorkflowTemplateResponse createTemplate(WorkflowTemplateCreateRequest request);

    WorkflowTemplateResponse getTemplate(String templateId);

    WorkflowTemplateResponse updateTemplate(String templateId, WorkflowTemplateUpdateRequest request);

    void deleteTemplate(String templateId);

    WorkflowResponse applyTemplate(String templateId, WorkflowTemplateApplyRequest request);
}
