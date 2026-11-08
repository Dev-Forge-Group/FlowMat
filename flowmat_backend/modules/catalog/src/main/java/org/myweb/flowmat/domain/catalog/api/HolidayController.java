package org.myweb.flowmat.domain.catalog.api;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.api.dto.request.HolidayRequest;
import org.myweb.flowmat.domain.catalog.api.dto.response.HolidayResponse;
import org.myweb.flowmat.domain.catalog.application.HolidayService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The project's holidays (docs/domain/equipment-schedule.md "휴일"); every change returns the whole list. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/holidays")
public class HolidayController {

    private final HolidayService holidayService;

    @GetMapping
    public ApiResponse<List<HolidayResponse>> list(@RequestParam("projectId") String projectId) {
        return ApiResponse.ok(holidayService.list(projectId));
    }

    @PostMapping
    public ApiResponse<List<HolidayResponse>> add(@RequestBody HolidayRequest request) {
        return ApiResponse.ok(holidayService.add(request));
    }

    @DeleteMapping("/{holidayId}")
    public ApiResponse<List<HolidayResponse>> remove(@PathVariable("holidayId") String holidayId) {
        return ApiResponse.ok(holidayService.remove(holidayId));
    }
}
