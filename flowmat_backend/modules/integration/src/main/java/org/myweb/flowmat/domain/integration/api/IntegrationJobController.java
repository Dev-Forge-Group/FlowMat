package org.myweb.flowmat.domain.integration.api;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.integration.api.dto.request.IntegrationJobRequest;
import org.myweb.flowmat.domain.integration.api.dto.response.IntegrationJobResponse;
import org.myweb.flowmat.domain.integration.api.dto.response.IntegrationRunResponse;
import org.myweb.flowmat.domain.integration.application.IntegrationJobService;
import org.myweb.flowmat.domain.integration.application.IntegrationRunService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A project's integration jobs and their runs (docs/architecture/adr/ADR-038-integration-jobs.md). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/projects/{projectId}/integration-jobs")
public class IntegrationJobController {

    private final IntegrationJobService jobs;
    private final IntegrationRunService runs;

    @GetMapping
    public ApiResponse<List<IntegrationJobResponse>> list(@PathVariable("projectId") String projectId) {
        return ApiResponse.ok(jobs.list(projectId));
    }

    @PostMapping
    public ApiResponse<IntegrationJobResponse> create(@PathVariable("projectId") String projectId,
                                                      @RequestBody IntegrationJobRequest request) {
        return ApiResponse.ok(jobs.create(projectId, request));
    }

    @PutMapping("/{jobId}")
    public ApiResponse<IntegrationJobResponse> update(@PathVariable("projectId") String projectId,
                                                      @PathVariable("jobId") String jobId,
                                                      @RequestBody IntegrationJobRequest request) {
        return ApiResponse.ok(jobs.update(projectId, jobId, request));
    }

    @DeleteMapping("/{jobId}")
    public ApiResponse<Void> delete(@PathVariable("projectId") String projectId, @PathVariable("jobId") String jobId) {
        jobs.delete(projectId, jobId);
        return ApiResponse.ok(null);
    }

    /** What a run would do now; nothing is saved. */
    @PostMapping("/{jobId}/preview")
    public ApiResponse<IntegrationRunResponse> preview(@PathVariable("projectId") String projectId,
                                                       @PathVariable("jobId") String jobId) {
        return ApiResponse.ok(runs.preview(projectId, jobId));
    }

    @PostMapping("/{jobId}/runs")
    public ApiResponse<IntegrationRunResponse> run(@PathVariable("projectId") String projectId, @PathVariable("jobId") String jobId) {
        return ApiResponse.ok(runs.runNow(projectId, jobId));
    }

    @GetMapping("/{jobId}/runs")
    public ApiResponse<List<IntegrationRunResponse>> runs(@PathVariable("projectId") String projectId,
                                                          @PathVariable("jobId") String jobId) {
        return ApiResponse.ok(jobs.runs(projectId, jobId));
    }
}
