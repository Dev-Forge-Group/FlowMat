package org.myweb.flowmat.domain.workflow.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessExecutionPolicyRequest;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessExecutionPolicyResponse;
import org.myweb.flowmat.domain.workflow.application.ProcessExecutionPolicyService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A node's execution policy (docs/domain/flow-run-execution-policy.md EP2). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/processes/{processId}/execution-policy")
public class ProcessExecutionPolicyController {

    private final ProcessExecutionPolicyService service;

    @GetMapping
    public ApiResponse<ProcessExecutionPolicyResponse> get(@PathVariable("processId") String processId) {
        return ApiResponse.ok(service.get(processId));
    }

    @PutMapping
    public ApiResponse<ProcessExecutionPolicyResponse> set(
        @PathVariable("processId") String processId,
        @RequestBody(required = false) ProcessExecutionPolicyRequest request
    ) {
        return ApiResponse.ok(service.set(processId, request));
    }
}
