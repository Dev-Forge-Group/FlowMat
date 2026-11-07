package org.myweb.flowmat.domain.catalog.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.api.dto.response.EquipmentCalendarHistoryResponse;
import org.myweb.flowmat.domain.catalog.domain.entity.EquipmentCalendar;
import org.myweb.flowmat.domain.catalog.domain.entity.EquipmentSchedule;
import org.myweb.flowmat.domain.catalog.domain.entity.EquipmentScheduleHistory;
import org.myweb.flowmat.domain.catalog.repository.EquipmentScheduleHistoryRepository;
import org.myweb.flowmat.domain.catalog.repository.EquipmentCalendarRepository;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The save history of equipment calendar versions (docs/architecture/adr/ADR-024-version-save-history.md): the schedule
 * service writes a row in the same transaction as each save and removal. Callers check access.
 */
@Service
@RequiredArgsConstructor
public class EquipmentScheduleHistoryService {
    private static final TypeReference<List<EquipmentCalendarHistoryResponse.Shift>> SHIFTS = new TypeReference<>() {
    };

    private final EquipmentScheduleHistoryRepository repository;
    private final ObjectMapper mapper;
    private final IdGenerator ids;
    private final EquipmentCalendarRepository calendars;
    private final JdbcTemplate jdbc;

    /** Writes down that {@code schedule} was saved or removed with these shifts (CV10). */
    void record(EquipmentSchedule schedule, String action, List<EquipmentCalendar> shifts, String actor, OffsetDateTime at) {
        Map<String, LocalTime[]> stored = storedTimes(schedule.getScheduleId(), shifts);
        EquipmentScheduleHistory row = new EquipmentScheduleHistory();
        row.setHistoryId(ids.generate());
        row.setEquipmentId(schedule.getEquipmentId());
        row.setScheduleId(schedule.getScheduleId());
        row.setEffectiveFrom(schedule.getEffectiveFrom());
        row.setAction(action);
        row.setReason(schedule.getReason());
        row.setShiftsJson(write(shifts.stream()
            .map(shift -> {
                LocalTime[] times = stored.get(shift.getShiftId());
                if (times == null) throw new IllegalStateException("The history shift has not been saved.");
                return new EquipmentCalendarHistoryResponse.Shift(times[0].toString(), times[1].toString(), days(shift.getWorkDays()));
            })
            .toList()));
        row.setSavedBy(actor);
        row.setSavedAt(at);
        repository.save(row);
    }

    /** Read all stored times together; managed objects can still carry precision that their JDBC mapping did not store. */
    private Map<String, LocalTime[]> storedTimes(String scheduleId, List<EquipmentCalendar> shifts) {
        if (shifts.isEmpty()) return Map.of();
        calendars.flush();
        return jdbc.query("select shift_id,shift_start,shift_end from equipment_calendar where schedule_id=?", rows -> {
            Map<String, LocalTime[]> times = new HashMap<>();
            while (rows.next()) times.put(rows.getString(1),
                new LocalTime[] { rows.getObject(2, LocalTime.class), rows.getObject(3, LocalTime.class) });
            return times;
        }, scheduleId);
    }

    /** The equipment's saves and removals, newest first, at most 100 (CV10). */
    List<EquipmentCalendarHistoryResponse> list(String equipmentId) {
        return repository.findTop100ByEquipmentIdOrderBySavedAtDescHistoryIdDesc(equipmentId).stream()
            .map(row -> new EquipmentCalendarHistoryResponse(row.getHistoryId(), row.getScheduleId(), row.getEffectiveFrom(),
                row.getAction(), row.getReason(), read(row.getShiftsJson()), row.getSavedBy(), row.getSavedAt()))
            .toList();
    }

    private static List<Integer> days(String workDays) {
        return workDays == null || workDays.isBlank() ? List.of()
            : Arrays.stream(workDays.split(",")).map(String::trim).map(Integer::valueOf).toList();
    }

    private String write(List<EquipmentCalendarHistoryResponse.Shift> shifts) {
        try {
            return mapper.writeValueAsString(shifts);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not write the calendar history.", exception);
        }
    }

    private List<EquipmentCalendarHistoryResponse.Shift> read(String json) {
        try {
            return mapper.readValue(json, SHIFTS);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored calendar history is invalid.", exception);
        }
    }
}
