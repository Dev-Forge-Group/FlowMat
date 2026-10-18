package org.myweb.flowmat.domain.catalog.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.api.dto.request.EquipmentCalendarRequest;
import org.myweb.flowmat.domain.catalog.api.dto.request.EquipmentDayCopyRequest;
import org.myweb.flowmat.domain.catalog.api.dto.request.EquipmentDayRequest;
import org.myweb.flowmat.domain.catalog.api.dto.request.EquipmentDowntimeRequest;
import org.myweb.flowmat.domain.catalog.api.dto.response.EquipmentAvailabilityResponse;
import org.myweb.flowmat.domain.catalog.api.dto.response.EquipmentCalendarVersionResponse;
import org.myweb.flowmat.domain.catalog.api.dto.response.EquipmentScheduleResponse;
import org.myweb.flowmat.domain.catalog.application.EquipmentScheduleService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Equipment calendar, downtime and available time (docs/domain/equipment-schedule.md). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/equipments/{equipmentId}")
public class EquipmentScheduleController {

    private final EquipmentScheduleService equipmentScheduleService;

    @GetMapping("/schedule")
    public ApiResponse<EquipmentScheduleResponse> schedule(@PathVariable("equipmentId") String equipmentId) {
        return ApiResponse.ok(equipmentScheduleService.schedule(equipmentId));
    }

    @PutMapping("/calendar")
    public ApiResponse<EquipmentScheduleResponse> setCalendar(
        @PathVariable("equipmentId") String equipmentId,
        @RequestBody EquipmentCalendarRequest request
    ) {
        return ApiResponse.ok(equipmentScheduleService.setCalendar(equipmentId, request));
    }

    /** From {@code effectiveFrom} (today when left out) the equipment runs around the clock; earlier dates keep theirs. */
    @DeleteMapping("/calendar")
    public ApiResponse<EquipmentScheduleResponse> clearCalendar(
        @PathVariable("equipmentId") String equipmentId,
        @RequestParam(value = "effectiveFrom", required = false) String effectiveFrom
    ) {
        return ApiResponse.ok(equipmentScheduleService.clearCalendar(equipmentId, effectiveFrom));
    }

    /** One version of the calendar (docs/architecture/adr/ADR-014-equipment-calendar-history.md). */
    @GetMapping("/calendar/versions/{scheduleId}")
    public ApiResponse<EquipmentCalendarVersionResponse> calendarVersion(
        @PathVariable("equipmentId") String equipmentId,
        @PathVariable("scheduleId") String scheduleId
    ) {
        return ApiResponse.ok(equipmentScheduleService.calendarVersion(equipmentId, scheduleId));
    }

    /** Deletes a calendar version that has not started yet (ADR-014). */
    @DeleteMapping("/calendar/versions/{scheduleId}")
    public ApiResponse<EquipmentScheduleResponse> removeCalendarVersion(
        @PathVariable("equipmentId") String equipmentId,
        @PathVariable("scheduleId") String scheduleId
    ) {
        return ApiResponse.ok(equipmentScheduleService.removeCalendarVersion(equipmentId, scheduleId));
    }

    /** One date's shifts in place of the calendar's; no shifts close the day (docs/domain/equipment-schedule.md). */
    @PutMapping("/days/{date}")
    public ApiResponse<EquipmentScheduleResponse> setDay(
        @PathVariable("equipmentId") String equipmentId,
        @PathVariable("date") String date,
        @RequestBody(required = false) EquipmentDayRequest request
    ) {
        return ApiResponse.ok(equipmentScheduleService.setDay(equipmentId, date, request));
    }

    /** The date, or with {@code through} every date of its own up to that one, back to the calendar (D6). */
    @DeleteMapping("/days/{date}")
    public ApiResponse<EquipmentScheduleResponse> clearDay(
        @PathVariable("equipmentId") String equipmentId,
        @PathVariable("date") String date,
        @RequestParam(value = "through", required = false) String through
    ) {
        return ApiResponse.ok(equipmentScheduleService.clearDay(equipmentId, date, through));
    }

    /** This equipment's own dates onto another equipment of the project; the answer is that equipment's schedule (D7). */
    @PostMapping("/days/copy")
    public ApiResponse<EquipmentScheduleResponse> copyDays(
        @PathVariable("equipmentId") String equipmentId,
        @RequestBody(required = false) EquipmentDayCopyRequest request
    ) {
        return ApiResponse.ok(equipmentScheduleService.copyDays(equipmentId, request));
    }

    @PostMapping("/downtimes")
    public ApiResponse<EquipmentScheduleResponse> addDowntime(
        @PathVariable("equipmentId") String equipmentId,
        @RequestBody EquipmentDowntimeRequest request
    ) {
        return ApiResponse.ok(equipmentScheduleService.addDowntime(equipmentId, request));
    }

    @DeleteMapping("/downtimes/{downtimeId}")
    public ApiResponse<EquipmentScheduleResponse> removeDowntime(
        @PathVariable("equipmentId") String equipmentId,
        @PathVariable("downtimeId") String downtimeId
    ) {
        return ApiResponse.ok(equipmentScheduleService.removeDowntime(equipmentId, downtimeId));
    }

    /** {@code from} and {@code to} are ISO date-times with an offset; the window is at most 366 days. */
    @GetMapping("/availability")
    public ApiResponse<EquipmentAvailabilityResponse> availability(
        @PathVariable("equipmentId") String equipmentId,
        @RequestParam("from") String from,
        @RequestParam("to") String to
    ) {
        return ApiResponse.ok(equipmentScheduleService.availability(equipmentId, from, to));
    }
}
