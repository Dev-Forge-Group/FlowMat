package org.myweb.flowmat.domain.catalog.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.api.dto.response.EquipmentDayHistoryResponse;
import org.myweb.flowmat.domain.catalog.domain.entity.EquipmentDayHistory;
import org.myweb.flowmat.domain.catalog.domain.entity.EquipmentDayOverride;
import org.myweb.flowmat.domain.catalog.domain.entity.EquipmentDowntime;
import org.myweb.flowmat.domain.catalog.repository.EquipmentDayHistoryRepository;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;

/**
 * The save history of equipment's own dates and downtimes (docs/architecture/adr/ADR-024-version-save-history.md decisions
 * 7-12): the schedule service writes a line in the same transaction as each change. Callers check access.
 */
@Service
@RequiredArgsConstructor
public class EquipmentDayHistoryService {
    private static final TypeReference<List<EquipmentDayHistoryResponse.Day>> DAYS = new TypeReference<>() {
    };

    private final EquipmentDayHistoryRepository repository;
    private final ObjectMapper mapper;
    private final IdGenerator ids;

    /** Writes down that these dates were saved with their new shifts or removed with what they had (CV12). */
    void recordDays(String equipmentId, String action, List<EquipmentDayOverride> rows, String reason, String copiedFrom,
                    String actor, OffsetDateTime at) {
        List<EquipmentDayOverride> dates = rows.stream().sorted(Comparator.comparing(EquipmentDayOverride::getOverrideDate)).toList();
        EquipmentDayHistory row = line(equipmentId, "day", action, actor, at);
        row.setFirstDate(dates.get(0).getOverrideDate());
        row.setLastDate(dates.get(dates.size() - 1).getOverrideDate());
        row.setDaysJson(write(dates.stream()
            .map(day -> new EquipmentDayHistoryResponse.Day(day.getOverrideDate(), shifts(day.getShifts()), day.getReason()))
            .toList()));
        row.setReason(reason);
        row.setCopiedFrom(copiedFrom);
        repository.save(row);
    }

    /** Writes down that the downtime was added or removed (CV12). */
    void recordDowntime(EquipmentDowntime downtime, String action, String actor, OffsetDateTime at) {
        EquipmentDayHistory row = line(downtime.getEquipmentId(), "downtime", action, actor, at);
        row.setDowntimeId(downtime.getDowntimeId());
        row.setDowntimeType(downtime.getDowntimeType());
        row.setStartsAt(downtime.getStartsAt());
        row.setEndsAt(downtime.getEndsAt());
        row.setReason(downtime.getReason());
        repository.save(row);
    }

    /** The equipment's date changes and downtimes, newest first, at most 100 (CV13). */
    List<EquipmentDayHistoryResponse> list(String equipmentId) {
        return repository.findTop100ByEquipmentIdOrderBySavedAtDescHistoryIdDesc(equipmentId).stream()
            .map(this::response)
            .toList();
    }

    /** One line as answered, for the latest list and the search. */
    EquipmentDayHistoryResponse response(EquipmentDayHistory row) {
        return new EquipmentDayHistoryResponse(row.getHistoryId(), row.getChangeKind(), row.getAction(), row.getFirstDate(),
            row.getLastDate(), row.getDaysJson() == null ? List.of() : read(row.getDaysJson()), row.getDowntimeId(),
            row.getDowntimeType(), row.getStartsAt(), row.getEndsAt(), row.getReason(), row.getCopiedFrom(), row.getSavedBy(),
            row.getSavedAt());
    }

    private EquipmentDayHistory line(String equipmentId, String kind, String action, String actor, OffsetDateTime at) {
        EquipmentDayHistory row = new EquipmentDayHistory();
        row.setHistoryId(ids.generate());
        row.setEquipmentId(equipmentId);
        row.setChangeKind(kind);
        row.setAction(action);
        row.setSavedBy(actor);
        row.setSavedAt(at);
        return row;
    }

    /** A date's shifts as stored, "06:00-14:00,14:00-18:00"; empty when the equipment does not work that day. */
    private static List<EquipmentDayHistoryResponse.Shift> shifts(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.split(","))
            .map(one -> one.trim().split("-"))
            .map(times -> new EquipmentDayHistoryResponse.Shift(times[0], times[1]))
            .toList();
    }

    private String write(List<EquipmentDayHistoryResponse.Day> days) {
        try {
            return mapper.writeValueAsString(days);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not write the day history.", exception);
        }
    }

    private List<EquipmentDayHistoryResponse.Day> read(String json) {
        try {
            return mapper.readValue(json, DAYS);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored day history is invalid.", exception);
        }
    }
}
