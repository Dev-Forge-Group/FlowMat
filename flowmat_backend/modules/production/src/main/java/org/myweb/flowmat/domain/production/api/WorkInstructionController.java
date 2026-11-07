package org.myweb.flowmat.domain.production.api;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.request.WorkInstructionRequest;
import org.myweb.flowmat.domain.production.api.dto.request.WorkInstructionStepRequest;
import org.myweb.flowmat.domain.production.api.dto.response.WorkInstructionResponse;
import org.myweb.flowmat.domain.production.application.WorkInstructionService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Work instruction revisions per product (docs/domain/work-instruction.md). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/work-instructions")
public class WorkInstructionController {

    private final WorkInstructionService workInstructionService;

    @GetMapping
    public ApiResponse<List<WorkInstructionResponse>> list(
        @RequestParam(value = "projectId", required = false) String projectId,
        @RequestParam(value = "itemId", required = false) String itemId
    ) {
        return ApiResponse.ok(workInstructionService.list(projectId, itemId));
    }

    @PostMapping
    public ApiResponse<WorkInstructionResponse> create(@RequestBody WorkInstructionRequest request) {
        return ApiResponse.ok(workInstructionService.create(request));
    }

    @PutMapping("/{instructionId}")
    public ApiResponse<WorkInstructionResponse> update(
        @PathVariable("instructionId") String instructionId,
        @RequestBody WorkInstructionRequest request
    ) {
        return ApiResponse.ok(workInstructionService.update(instructionId, request));
    }

    @DeleteMapping("/{instructionId}")
    public ApiResponse<Void> delete(@PathVariable("instructionId") String instructionId) {
        workInstructionService.delete(instructionId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{instructionId}/steps")
    public ApiResponse<WorkInstructionResponse> addStep(
        @PathVariable("instructionId") String instructionId,
        @RequestBody WorkInstructionStepRequest request
    ) {
        return ApiResponse.ok(workInstructionService.addStep(instructionId, request));
    }

    @DeleteMapping("/{instructionId}/steps/{stepId}")
    public ApiResponse<WorkInstructionResponse> removeStep(
        @PathVariable("instructionId") String instructionId,
        @PathVariable("stepId") String stepId
    ) {
        return ApiResponse.ok(workInstructionService.removeStep(instructionId, stepId));
    }

    /** Project owner only; the revision released before it is retired. */
    @PostMapping("/{instructionId}/release")
    public ApiResponse<WorkInstructionResponse> release(@PathVariable("instructionId") String instructionId) {
        return ApiResponse.ok(workInstructionService.release(instructionId));
    }

    @PostMapping("/{instructionId}/revise")
    public ApiResponse<WorkInstructionResponse> revise(@PathVariable("instructionId") String instructionId) {
        return ApiResponse.ok(workInstructionService.revise(instructionId));
    }
}
