package org.myweb.flowmat.domain.connector.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.connector.api.dto.response.JsonMappingPreviewResponse;
import org.myweb.flowmat.domain.connector.application.JsonMappingPreviewService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Bounded JSON mapping preview; no HTTP target or persisted execution is involved. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/projects/{projectId}/json-mapping/preview")
public class JsonMappingPreviewController {
    private final JsonMappingPreviewService service;

    @PostMapping
    public ApiResponse<JsonMappingPreviewResponse> preview(@PathVariable("projectId") String projectId,
        HttpServletRequest request) throws IOException {
        return ApiResponse.ok(service.preview(projectId, request.getInputStream()));
    }
}
