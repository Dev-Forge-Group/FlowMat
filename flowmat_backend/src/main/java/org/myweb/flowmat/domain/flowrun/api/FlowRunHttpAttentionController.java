package org.myweb.flowmat.domain.flowrun.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.flowrun.api.dto.response.FlowRunHttpAttentionPageResponse;
import org.myweb.flowmat.domain.flowrun.application.FlowRunHttpAttentionService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/projects/{projectId}/http-executions/attention")
public class FlowRunHttpAttentionController {
    private final FlowRunHttpAttentionService service;

    @GetMapping
    public ApiResponse<FlowRunHttpAttentionPageResponse> search(@PathVariable String projectId,
        @RequestParam(required = false) String limit, @RequestParam(required = false) String cursor,
        @RequestParam(required = false) String state) {
        return ApiResponse.ok(service.search(projectId, limit, cursor, state));
    }
}
