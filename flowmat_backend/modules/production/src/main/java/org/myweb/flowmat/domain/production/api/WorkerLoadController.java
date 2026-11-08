package org.myweb.flowmat.domain.production.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.response.WorkerLoadResponse;
import org.myweb.flowmat.domain.production.application.WorkerLoadService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Each worker's shift hours against the work orders that name them (docs/domain/worker-calendar.md WC18). Project read. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/worker-load")
public class WorkerLoadController {

    private final WorkerLoadService workerLoadService;

    /** {@code from} and {@code to} are ISO date-times with an offset; the window is at most 92 days. */
    @GetMapping
    public ApiResponse<WorkerLoadResponse> load(
        @RequestParam(value = "projectId", required = false) String projectId,
        @RequestParam(value = "from", required = false) String from,
        @RequestParam(value = "to", required = false) String to
    ) {
        return ApiResponse.ok(workerLoadService.load(projectId, from, to));
    }
}
