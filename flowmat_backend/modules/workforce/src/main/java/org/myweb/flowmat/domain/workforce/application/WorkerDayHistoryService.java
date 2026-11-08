package org.myweb.flowmat.domain.workforce.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.workforce.api.dto.response.WorkerDayHistoryResponse;
import org.myweb.flowmat.domain.workforce.domain.entity.WorkerDayHistory;
import org.myweb.flowmat.domain.workforce.domain.entity.WorkerDayOff;
import org.myweb.flowmat.domain.workforce.domain.entity.WorkerDayShift;
import org.myweb.flowmat.domain.workforce.repository.WorkerDayHistoryRepository;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;

/**
 * The save history of workers' own dates and days off (docs/architecture/adr/ADR-024-version-save-history.md decisions
 * 7-12): the calendar service writes a line in the same transaction as each change. Callers check access.
 */
@Service
@RequiredArgsConstructor
public class WorkerDayHistoryService {
    private static final TypeReference<List<WorkerDayHistoryResponse.Day>> DAYS = new TypeReference<>() {
    };

    private final WorkerDayHistoryRepository repository;
    private final ObjectMapper mapper;
    private final IdGenerator ids;

    /** Writes down that these dates were saved with their new shifts or removed with what they had (WC65). */
    void recordDays(String projectId, String userId, String action, List<WorkerDayShift> rows, String reason, String copiedFrom,
                    String actor, OffsetDateTime at) {
        List<WorkerDayShift> dates = rows.stream().sorted(Comparator.comparing(WorkerDayShift::getWorkDate)).toList();
        WorkerDayHistory row = line(projectId, userId, "day", action, actor, at);
        row.setFirstDate(dates.get(0).getWorkDate());
        row.setLastDate(dates.get(dates.size() - 1).getWorkDate());
        row.setDaysJson(write(dates.stream()
            .map(day -> new WorkerDayHistoryResponse.Day(day.getWorkDate(), shifts(day.getShifts()), day.getReason()))
            .toList()));
        row.setReason(reason);
        row.setCopiedFrom(copiedFrom);
        repository.save(row);
    }

    /** Writes down that the day off was added or removed (WC65). */
    void recordDayOff(WorkerDayOff dayOff, String action, String actor, OffsetDateTime at) {
        WorkerDayHistory row = line(dayOff.getProjectId(), dayOff.getUserId(), "day_off", action, actor, at);
        row.setFirstDate(dayOff.getFirstDay());
        row.setLastDate(dayOff.getLastDay());
        row.setDayOffId(dayOff.getDayOffId());
        row.setReason(dayOff.getReason());
        repository.save(row);
    }

    /** The worker's date changes and days off, newest first, at most 100 (WC66). */
    List<WorkerDayHistoryResponse> list(String projectId, String userId) {
        return repository.findTop100ByProjectIdAndUserIdOrderBySavedAtDescHistoryIdDesc(projectId, userId).stream()
            .map(this::response)
            .toList();
    }

    /** One line as answered, for the latest list and the search. */
    WorkerDayHistoryResponse response(WorkerDayHistory row) {
        return new WorkerDayHistoryResponse(row.getHistoryId(), row.getChangeKind(), row.getAction(), row.getFirstDate(),
            row.getLastDate(), row.getDaysJson() == null ? List.of() : read(row.getDaysJson()), row.getDayOffId(),
            row.getReason(), row.getCopiedFrom(), row.getSavedBy(), row.getSavedAt());
    }

    private WorkerDayHistory line(String projectId, String userId, String kind, String action, String actor, OffsetDateTime at) {
        WorkerDayHistory row = new WorkerDayHistory();
        row.setHistoryId(ids.generate());
        row.setProjectId(projectId);
        row.setUserId(userId);
        row.setChangeKind(kind);
        row.setAction(action);
        row.setSavedBy(actor);
        row.setSavedAt(at);
        return row;
    }

    /** A date's shifts as stored, "06:00-14:00,14:00-18:00" (WC29); empty when there is no work that day. */
    private static List<WorkerDayHistoryResponse.Shift> shifts(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.split(","))
            .map(one -> one.trim().split("-"))
            .map(times -> new WorkerDayHistoryResponse.Shift(times[0], times[1]))
            .toList();
    }

    private String write(List<WorkerDayHistoryResponse.Day> days) {
        try {
            return mapper.writeValueAsString(days);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not write the day history.", exception);
        }
    }

    private List<WorkerDayHistoryResponse.Day> read(String json) {
        try {
            return mapper.readValue(json, DAYS);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored day history is invalid.", exception);
        }
    }
}
