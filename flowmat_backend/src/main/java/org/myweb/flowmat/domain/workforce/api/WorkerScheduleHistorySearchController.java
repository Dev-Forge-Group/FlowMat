package org.myweb.flowmat.domain.workforce.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.workforce.api.dto.response.WorkerScheduleHistoryResponse;
import org.myweb.flowmat.domain.workforce.application.WorkerScheduleHistorySearchService;
import org.myweb.flowmat.global.history.HistoryPageResponse;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only search beyond the legacy latest 100 entries. from is inclusive and to exclusive, both ISO instants with an
 * offset and a year in 0001..9999. action is saved/removed and savedBy an exact user ID. limit defaults to 100, allowed 1..100. Keep filters
 * unchanged when following nextCursor; restart without a cursor to include newly saved entries.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/projects/{projectId}/workers/{userId}/schedule-history/search")
public class WorkerScheduleHistorySearchController {
    private final WorkerScheduleHistorySearchService service;

    @GetMapping
    public ApiResponse<HistoryPageResponse<WorkerScheduleHistoryResponse>> search(
        @PathVariable("projectId") String projectId, @PathVariable("userId") String userId,
        @RequestParam(value = "action", required = false) String action,
        @RequestParam(value = "savedBy", required = false) String savedBy,
        @RequestParam(value = "from", required = false) String from,
        @RequestParam(value = "to", required = false) String to,
        @RequestParam(value = "limit", required = false) String limit,
        @RequestParam(value = "cursor", required = false) String cursor
    ) {
        return ApiResponse.ok(service.search(projectId, userId, action, savedBy, from, to, limit, cursor));
    }
}
