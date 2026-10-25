package org.myweb.flowmat.domain.workforce.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.workforce.api.dto.request.WorkerAttendanceCancelRequest;
import org.myweb.flowmat.domain.workforce.api.dto.request.WorkerAttendanceClockOutRequest;
import org.myweb.flowmat.domain.workforce.api.dto.request.WorkerAttendanceRequest;
import org.myweb.flowmat.domain.workforce.api.dto.response.WorkerAttendanceResponse;
import org.myweb.flowmat.domain.workforce.application.WorkerAttendanceService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A worker's attendance, for the worker themselves and the project owner (docs/architecture/adr/ADR-026-attendance.md). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/projects/{projectId}/workers/{userId}/attendance")
public class WorkerAttendanceController {

    private final WorkerAttendanceService service;

    @GetMapping
    public ApiResponse<WorkerAttendanceResponse> attendance(@PathVariable("projectId") String projectId,
                                                            @PathVariable("userId") String userId,
                                                            @RequestParam(value = "from", required = false) String from,
                                                            @RequestParam(value = "to", required = false) String to) {
        return ApiResponse.ok(service.attendance(projectId, userId, from, to));
    }

    @PostMapping
    public ApiResponse<WorkerAttendanceResponse> record(@PathVariable("projectId") String projectId,
                                                        @PathVariable("userId") String userId,
                                                        @RequestBody(required = false) WorkerAttendanceRequest request) {
        return ApiResponse.ok(service.record(projectId, userId, request));
    }

    @PostMapping("/{attendanceId}/clock-out")
    public ApiResponse<WorkerAttendanceResponse> clockOut(@PathVariable("projectId") String projectId,
                                                          @PathVariable("userId") String userId,
                                                          @PathVariable("attendanceId") String attendanceId,
                                                          @RequestBody(required = false) WorkerAttendanceClockOutRequest request) {
        return ApiResponse.ok(service.clockOut(projectId, userId, attendanceId, request));
    }

    @PostMapping("/{attendanceId}/cancel")
    public ApiResponse<WorkerAttendanceResponse> cancel(@PathVariable("projectId") String projectId,
                                                        @PathVariable("userId") String userId,
                                                        @PathVariable("attendanceId") String attendanceId,
                                                        @RequestBody(required = false) WorkerAttendanceCancelRequest request) {
        return ApiResponse.ok(service.cancel(projectId, userId, attendanceId, request));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.error("Send attendance as JSON, with requestId as a UUID."));
    }
}
