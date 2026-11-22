package org.myweb.flowmat.domain.production.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.response.WorkOrderWorkingTimeResponse;
import org.myweb.flowmat.domain.production.application.WorkOrderWorkingTimeService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * When a work order's equipment and crew work together (docs/architecture/adr/ADR-041-try-it-time.md decision 5).
 * {@code from} is an ISO date-time with an offset; {@code until} defaults to seven days later and is at most 366 days on.
 */
@RestController
@RequiredArgsConstructor
public class WorkOrderWorkingTimeController {

    private final WorkOrderWorkingTimeService service;

    @GetMapping("/work-orders/{workOrderId}/working-time")
    public ApiResponse<WorkOrderWorkingTimeResponse> workingTime(@PathVariable("workOrderId") String workOrderId,
                                                                 @RequestParam(value = "from", required = false) String from,
                                                                 @RequestParam(value = "until", required = false) String until) {
        return ApiResponse.ok(service.workingTime(workOrderId, from, until));
    }
}
