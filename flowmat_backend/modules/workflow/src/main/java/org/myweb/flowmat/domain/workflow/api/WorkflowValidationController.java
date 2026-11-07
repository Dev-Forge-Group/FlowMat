package org.myweb.flowmat.domain.workflow.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.workflow.api.dto.response.WorkflowValidationResponse;
import org.myweb.flowmat.domain.workflow.application.WorkflowValidationService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/workflows/{workflowId}/validation")
public class WorkflowValidationController {
    private final WorkflowValidationService validation;

    @GetMapping
    public ApiResponse<WorkflowValidationResponse> validate(@PathVariable String workflowId) {
        return ApiResponse.ok(validation.validate(workflowId));
    }
}
