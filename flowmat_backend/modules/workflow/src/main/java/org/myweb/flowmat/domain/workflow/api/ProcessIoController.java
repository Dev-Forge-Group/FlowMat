package org.myweb.flowmat.domain.workflow.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessIoCreateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessIoUpdateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessIoResponse;
import org.myweb.flowmat.domain.workflow.application.ProcessIoService;
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

@RestController
@RequiredArgsConstructor
@RequestMapping("/process-ios")
public class ProcessIoController {

    private final ProcessIoService processIoService;

    @GetMapping
    public ApiResponse<List<ProcessIoResponse>> listProcessIos(@RequestParam("processId") String processId) {
        return ApiResponse.ok(processIoService.listProcessIos(processId));
    }

    @PostMapping
    public ApiResponse<ProcessIoResponse> createProcessIo(@Valid @RequestBody ProcessIoCreateRequest request) {
        return ApiResponse.ok(processIoService.createProcessIo(request));
    }

    @GetMapping("/{processIoId}")
    public ApiResponse<ProcessIoResponse> getProcessIo(@PathVariable("processIoId") String processIoId) {
        return ApiResponse.ok(processIoService.getProcessIo(processIoId));
    }

    @PutMapping("/{processIoId}")
    public ApiResponse<ProcessIoResponse> updateProcessIo(
        @PathVariable("processIoId") String processIoId,
        @Valid @RequestBody ProcessIoUpdateRequest request
    ) {
        return ApiResponse.ok(processIoService.updateProcessIo(processIoId, request));
    }

    @DeleteMapping("/{processIoId}")
    public ApiResponse<Void> deleteProcessIo(@PathVariable("processIoId") String processIoId) {
        processIoService.deleteProcessIo(processIoId);
        return ApiResponse.ok(null);
    }
}
