package org.myweb.flowmat.domain.workforce.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.workforce.api.dto.response.WorkerDayHistoryResponse;
import org.myweb.flowmat.domain.workforce.application.WorkerDayHistorySearchService;
import org.myweb.flowmat.global.history.HistoryPageResponse;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A worker's date and day-off history beyond the latest 100 entries: the version history search's filters (action, savedBy,
 * from inclusive, to exclusive, limit 1..100, cursor) plus kind day or day_off. Keep the filters when following nextCursor.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/projects/{projectId}/workers/{userId}/day-history/search")
public class WorkerDayHistorySearchController {
    private final WorkerDayHistorySearchService service;

    @GetMapping
    public ApiResponse<HistoryPageResponse<WorkerDayHistoryResponse>> search(
        @PathVariable("projectId") String projectId, @PathVariable("userId") String userId,
        @RequestParam(value = "kind", required = false) String kind,
        @RequestParam(value = "action", required = false) String action,
        @RequestParam(value = "savedBy", required = false) String savedBy,
        @RequestParam(value = "from", required = false) String from,
        @RequestParam(value = "to", required = false) String to,
        @RequestParam(value = "limit", required = false) String limit,
        @RequestParam(value = "cursor", required = false) String cursor
    ) {
        return ApiResponse.ok(service.search(projectId, userId, kind, action, savedBy, from, to, limit, cursor));
    }
}
