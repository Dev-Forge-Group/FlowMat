package org.myweb.flowmat.domain.workforce.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.workforce.api.dto.response.WorkerScheduleHistoryResponse;
import org.myweb.flowmat.domain.workforce.domain.entity.WorkerRota;
import org.myweb.flowmat.domain.workforce.domain.entity.WorkerSchedule;
import org.myweb.flowmat.domain.workforce.domain.entity.WorkerScheduleHistory;
import org.myweb.flowmat.domain.workforce.domain.entity.WorkerShift;
import org.myweb.flowmat.domain.workforce.repository.WorkerScheduleHistoryRepository;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;

/**
 * The save history of workers' schedule versions (docs/architecture/adr/ADR-024-version-save-history.md): the calendar
 * service writes a row in the same transaction as each save and removal. Callers check access.
 */
@Service
@RequiredArgsConstructor
public class WorkerScheduleHistoryService {
    private static final TypeReference<List<WorkerScheduleHistoryResponse.Shift>> SHIFTS = new TypeReference<>() {
    };

    private final WorkerScheduleHistoryRepository repository;
    private final ObjectMapper mapper;
    private final IdGenerator ids;

    /** Writes down that {@code schedule} was saved or removed with these shifts and rota (WC63). */
    void record(WorkerSchedule schedule, String action, List<WorkerShift> shifts, WorkerRota rota, String actor, OffsetDateTime at) {
        WorkerScheduleHistory row = new WorkerScheduleHistory();
        row.setHistoryId(ids.generate());
        row.setProjectId(schedule.getProjectId());
        row.setUserId(schedule.getUserId());
        row.setScheduleId(schedule.getScheduleId());
        row.setEffectiveFrom(schedule.getEffectiveFrom());
        row.setAction(action);
        row.setReason(schedule.getReason());
        row.setShiftsJson(write(shifts.stream()
            .map(shift -> new WorkerScheduleHistoryResponse.Shift(shift.getShiftStart().toString(), shift.getShiftEnd().toString(),
                days(shift.getWorkDays()), shift.getRotaWeek()))
            .toList()));
        row.setRotaWeeks(rota == null ? null : rota.getCycleWeeks());
        row.setRotaStartsOn(rota == null ? null : rota.getStartsOn());
        row.setSavedBy(actor);
        row.setSavedAt(at);
        repository.save(row);
    }

    /** The worker's saves and removals, newest first, at most 100 (WC63). */
    List<WorkerScheduleHistoryResponse> list(String projectId, String userId) {
        return repository.findTop100ByProjectIdAndUserIdOrderBySavedAtDescHistoryIdDesc(projectId, userId).stream()
            .map(row -> new WorkerScheduleHistoryResponse(row.getHistoryId(), row.getScheduleId(), row.getEffectiveFrom(),
                row.getAction(), row.getReason(), read(row.getShiftsJson()),
                row.getRotaWeeks() == null ? null : new WorkerScheduleHistoryResponse.Rota(row.getRotaWeeks(), row.getRotaStartsOn()),
                row.getSavedBy(), row.getSavedAt()))
            .toList();
    }

    private static List<Integer> days(String workDays) {
        return workDays == null || workDays.isBlank() ? List.of()
            : Arrays.stream(workDays.split(",")).map(String::trim).map(Integer::valueOf).toList();
    }

    private String write(List<WorkerScheduleHistoryResponse.Shift> shifts) {
        try {
            return mapper.writeValueAsString(shifts);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not write the schedule history.", exception);
        }
    }

    private List<WorkerScheduleHistoryResponse.Shift> read(String json) {
        try {
            return mapper.readValue(json, SHIFTS);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored schedule history is invalid.", exception);
        }
    }
}
