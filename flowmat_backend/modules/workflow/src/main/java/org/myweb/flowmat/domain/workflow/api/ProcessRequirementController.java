package org.myweb.flowmat.domain.workflow.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessRequirementsRequest;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessRequirementResponse;
import org.myweb.flowmat.domain.workflow.application.ProcessRequirementService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A node's execution requirements (docs/architecture/adr/ADR-043-plan-backwards-from-a-target.md decision 4). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/processes/{processId}/requirements")
public class ProcessRequirementController {

    private final ProcessRequirementService service;

    @GetMapping
    public ApiResponse<List<ProcessRequirementResponse>> list(@PathVariable("processId") String processId) {
        return ApiResponse.ok(service.list(processId));
    }

    @PutMapping
    public ApiResponse<List<ProcessRequirementResponse>> replace(@PathVariable("processId") String processId,
        @Valid @RequestBody ProcessRequirementsRequest request) {
        return ApiResponse.ok(service.replace(processId, request));
    }
}
