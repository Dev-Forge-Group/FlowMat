package org.myweb.flowmat.domain.workflow.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessExecutionBindingRequest;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessExecutionBindingResponse;
import org.myweb.flowmat.domain.workflow.application.ProcessExecutionBindingService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/processes/{processId}/execution-binding")
public class ProcessExecutionBindingController {
    private final ProcessExecutionBindingService service;

    @GetMapping
    public ApiResponse<ProcessExecutionBindingResponse> get(@PathVariable("processId") String processId) {
        return ApiResponse.ok(service.get(processId));
    }

    @PutMapping
    public ApiResponse<ProcessExecutionBindingResponse> set(@PathVariable("processId") String processId,
        HttpServletRequest request) throws IOException {
        service.authorizeWrite(processId);
        ProcessExecutionBindingRequest body = ProcessExecutionBindingRequest.read(request.getInputStream());
        return ApiResponse.ok(service.set(processId, body));
    }
}
