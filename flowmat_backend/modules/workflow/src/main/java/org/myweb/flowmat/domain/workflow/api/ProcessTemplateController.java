package org.myweb.flowmat.domain.workflow.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessTemplateApplyRequest;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessTemplateCreateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessTemplateUpdateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessResponse;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessTemplateResponse;
import org.myweb.flowmat.domain.workflow.application.ProcessTemplateService;
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
@RequestMapping("/process-templates")
public class ProcessTemplateController {

    private final ProcessTemplateService processTemplateService;

    @GetMapping
    public ApiResponse<List<ProcessTemplateResponse>> listTemplates() {
        return ApiResponse.ok(processTemplateService.listTemplates());
    }

    @PostMapping
    public ApiResponse<ProcessTemplateResponse> createTemplate(@Valid @RequestBody ProcessTemplateCreateRequest request) {
        return ApiResponse.ok(processTemplateService.createTemplate(request));
    }

    @GetMapping("/{templateId}")
    public ApiResponse<ProcessTemplateResponse> getTemplate(@PathVariable("templateId") String templateId) {
        return ApiResponse.ok(processTemplateService.getTemplate(templateId));
    }

    @PutMapping("/{templateId}")
    public ApiResponse<ProcessTemplateResponse> updateTemplate(
        @PathVariable("templateId") String templateId,
        @RequestBody ProcessTemplateUpdateRequest request
    ) {
        return ApiResponse.ok(processTemplateService.updateTemplate(templateId, request));
    }

    @DeleteMapping("/{templateId}")
    public ApiResponse<Void> deleteTemplate(@PathVariable("templateId") String templateId) {
        processTemplateService.deleteTemplate(templateId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{templateId}/apply")
    public ApiResponse<ProcessResponse> applyTemplate(
        @PathVariable("templateId") String templateId,
        @Valid @RequestBody ProcessTemplateApplyRequest request
    ) {
        return ApiResponse.ok(processTemplateService.applyTemplate(templateId, request));
    }
}
