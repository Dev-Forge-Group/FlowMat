package org.myweb.flowmat.domain.workflow.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessCreateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessPositionRequest;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessUpdateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessResponse;
import org.myweb.flowmat.domain.workflow.application.ProcessService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/processes")
public class ProcessController {

    private final ProcessService processService;

    @GetMapping
    public ApiResponse<List<ProcessResponse>> listProcesses(@RequestParam("workflowId") String workflowId) {
        return ApiResponse.ok(processService.listProcesses(workflowId));
    }

    @PostMapping
    public ApiResponse<ProcessResponse> createProcess(@Valid @RequestBody ProcessCreateRequest request) {
        return ApiResponse.ok(processService.createProcess(request));
    }

    @GetMapping("/{processId}")
    public ApiResponse<ProcessResponse> getProcess(@PathVariable("processId") String processId) {
        return ApiResponse.ok(processService.getProcess(processId));
    }

    @PutMapping("/{processId}")
    public ApiResponse<ProcessResponse> updateProcess(
        @PathVariable("processId") String processId,
        @RequestBody ProcessUpdateRequest request
    ) {
        return ApiResponse.ok(processService.updateProcess(processId, request));
    }

    @PatchMapping("/{processId}/position")
    public ApiResponse<ProcessResponse> updatePosition(
        @PathVariable("processId") String processId,
        @Valid @RequestBody ProcessPositionRequest request
    ) {
        return ApiResponse.ok(processService.updatePosition(processId, request));
    }

    @DeleteMapping("/{processId}")
    public ApiResponse<Void> deleteProcess(@PathVariable("processId") String processId) {
        processService.deleteProcess(processId);
        return ApiResponse.ok(null);
    }
}
