package org.myweb.flowmat.domain.workflow.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.workflow.api.dto.request.WorkflowTemplateApplyRequest;
import org.myweb.flowmat.domain.workflow.api.dto.request.WorkflowTemplateCreateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.request.WorkflowTemplateUpdateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.response.WorkflowResponse;
import org.myweb.flowmat.domain.workflow.api.dto.response.WorkflowTemplateResponse;
import org.myweb.flowmat.domain.workflow.application.WorkflowTemplateService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/workflow-templates")
public class WorkflowTemplateController {

    private final WorkflowTemplateService workflowTemplateService;

    @GetMapping
    public ApiResponse<List<WorkflowTemplateResponse>> listTemplates() {
        return ApiResponse.ok(workflowTemplateService.listTemplates());
    }

    @PostMapping
    public ApiResponse<WorkflowTemplateResponse> createTemplate(@Valid @RequestBody WorkflowTemplateCreateRequest request) {
        return ApiResponse.ok(workflowTemplateService.createTemplate(request));
    }

    @GetMapping("/{templateId}")
    public ApiResponse<WorkflowTemplateResponse> getTemplate(@PathVariable("templateId") String templateId) {
        return ApiResponse.ok(workflowTemplateService.getTemplate(templateId));
    }

    @PutMapping("/{templateId}")
    public ApiResponse<WorkflowTemplateResponse> updateTemplate(
        @PathVariable("templateId") String templateId,
        @RequestBody WorkflowTemplateUpdateRequest request
    ) {
        return ApiResponse.ok(workflowTemplateService.updateTemplate(templateId, request));
    }

    @DeleteMapping("/{templateId}")
    public ApiResponse<Void> deleteTemplate(@PathVariable("templateId") String templateId) {
        workflowTemplateService.deleteTemplate(templateId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{templateId}/apply")
    public ApiResponse<WorkflowResponse> applyTemplate(
        @PathVariable("templateId") String templateId,
        @Valid @RequestBody WorkflowTemplateApplyRequest request
    ) {
        return ApiResponse.ok(workflowTemplateService.applyTemplate(templateId, request));
    }
}
