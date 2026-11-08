package org.myweb.flowmat.domain.catalog.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.api.dto.response.EquipmentCalendarHistoryResponse;
import org.myweb.flowmat.domain.catalog.domain.entity.EquipmentScheduleHistory;
import org.myweb.flowmat.domain.catalog.repository.EquipmentRepository;
import org.myweb.flowmat.domain.catalog.repository.EquipmentScheduleHistorySearchRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.history.HistoryPageResponse;
import org.myweb.flowmat.global.history.HistorySearchFilter;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EquipmentScheduleHistorySearchService {
    private static final TypeReference<List<EquipmentCalendarHistoryResponse.Shift>> SHIFTS = new TypeReference<>() {};
    private final EquipmentScheduleHistorySearchRepository repository;
    private final EquipmentRepository equipmentRepository;
    private final ProjectAccessService access;
    private final ObjectMapper mapper;

    public HistoryPageResponse<EquipmentCalendarHistoryResponse> search(String equipmentId, String action, String savedBy,
                                                                       String from, String to, String limit, String cursor) {
        var equipment = equipmentRepository.findByEquipmentIdAndDeletedYn(equipmentId, "N")
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        access.requireProjectReadAccess(equipment.getProjectId());
        HistorySearchFilter filter = HistorySearchFilter.parse(action, savedBy, from, to, limit, cursor);
        EquipmentScheduleHistory anchor = filter.cursor() == null ? null : repository.findByHistoryIdAndEquipmentId(filter.cursor(), equipmentId)
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "cursor must identify an entry in this equipment's history."));
        Specification<EquipmentScheduleHistory> scope = (root, query, cb) -> cb.equal(root.get("equipmentId"), equipmentId);
        var spec = scope.and(filter.specification(anchor == null ? null : anchor.getSavedAt(), anchor == null ? null : anchor.getHistoryId()));
        var rows = repository.findBy(spec,
            query -> query.sortBy(Sort.by(Sort.Order.desc("savedAt"), Sort.Order.desc("historyId"))).limit(filter.limit() + 1).all());
        boolean more = rows.size() > filter.limit();
        var page = more ? rows.subList(0, filter.limit()) : rows;
        String next = more ? page.get(page.size() - 1).getHistoryId() : null;
        return new HistoryPageResponse<>(page.stream().map(this::response).toList(), next);
    }

    private EquipmentCalendarHistoryResponse response(EquipmentScheduleHistory row) {
        try {
            return new EquipmentCalendarHistoryResponse(row.getHistoryId(), row.getScheduleId(), row.getEffectiveFrom(), row.getAction(),
                row.getReason(), mapper.readValue(row.getShiftsJson(), SHIFTS), row.getSavedBy(), row.getSavedAt());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored calendar history is invalid.", exception);
        }
    }
}
