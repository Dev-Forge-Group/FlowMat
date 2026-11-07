package org.myweb.flowmat.domain.production.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.response.AttendanceComparisonResponse;
import org.myweb.flowmat.domain.production.application.AttendanceComparisonService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A worker's attendance beside the worked time recorded on runs (docs/architecture/adr/ADR-026-attendance.md). */
@RestController
@RequiredArgsConstructor
public class AttendanceComparisonController {

    private final AttendanceComparisonService service;

    @GetMapping("/projects/{projectId}/workers/{userId}/attendance-comparison")
    public ApiResponse<AttendanceComparisonResponse> compare(@PathVariable("projectId") String projectId,
                                                             @PathVariable("userId") String userId,
                                                             @RequestParam(value = "from", required = false) String from,
                                                             @RequestParam(value = "to", required = false) String to) {
        return ApiResponse.ok(service.compare(projectId, userId, from, to));
    }
}
