package org.myweb.flowmat.domain.workflow.application;

import java.util.List;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessTemplateApplyRequest;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessTemplateCreateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessTemplateUpdateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessResponse;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessTemplateResponse;

public interface ProcessTemplateService {

    List<ProcessTemplateResponse> listTemplates();

    ProcessTemplateResponse createTemplate(ProcessTemplateCreateRequest request);

    ProcessTemplateResponse getTemplate(String templateId);

    ProcessTemplateResponse updateTemplate(String templateId, ProcessTemplateUpdateRequest request);

    void deleteTemplate(String templateId);

    ProcessResponse applyTemplate(String templateId, ProcessTemplateApplyRequest request);
}
