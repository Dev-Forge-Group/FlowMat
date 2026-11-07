package org.myweb.flowmat.domain.workflow.api;

import com.fasterxml.jackson.databind.JsonMappingException;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessConnectionCreateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessConnectionUpdateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessConnectionResponse;
import org.myweb.flowmat.domain.workflow.application.ProcessConnectionService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
@RequestMapping("/process-connections")
public class ProcessConnectionController {

    private static final Set<String> NUMBER_FIELDS = Set.of("flowRate", "delayTimeSec", "lossRate", "capacity", "priority");
    private final ProcessConnectionService processConnectionService;

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableRequest(HttpMessageNotReadableException exception) {
        String message = "Request body must contain valid JSON.";
        if (exception.getCause() instanceof JsonMappingException mappingException) {
            message = mappingException.getPath().stream()
                .map(JsonMappingException.Reference::getFieldName)
                .filter(field -> field != null && NUMBER_FIELDS.contains(field))
                .findFirst()
                .map(field -> field + " could not be read from the request body.")
                .orElse(message);
        }
        return ResponseEntity.badRequest().body(ApiResponse.error(message));
    }

    @GetMapping
    public ApiResponse<List<ProcessConnectionResponse>> listConnections(@RequestParam("workflowId") String workflowId) {
        return ApiResponse.ok(processConnectionService.listConnections(workflowId));
    }

    @PostMapping
    public ApiResponse<ProcessConnectionResponse> createConnection(
        @Valid @RequestBody ProcessConnectionCreateRequest request
    ) {
        return ApiResponse.ok(processConnectionService.createConnection(request));
    }

    @GetMapping("/{connectionId}")
    public ApiResponse<ProcessConnectionResponse> getConnection(@PathVariable("connectionId") String connectionId) {
        return ApiResponse.ok(processConnectionService.getConnection(connectionId));
    }

    @PutMapping("/{connectionId}")
    public ApiResponse<ProcessConnectionResponse> updateConnection(
        @PathVariable("connectionId") String connectionId,
        @Valid @RequestBody ProcessConnectionUpdateRequest request
    ) {
        return ApiResponse.ok(processConnectionService.updateConnection(connectionId, request));
    }

    @DeleteMapping("/{connectionId}")
    public ApiResponse<Void> deleteConnection(@PathVariable("connectionId") String connectionId) {
        processConnectionService.deleteConnection(connectionId);
        return ApiResponse.ok(null);
    }
}
