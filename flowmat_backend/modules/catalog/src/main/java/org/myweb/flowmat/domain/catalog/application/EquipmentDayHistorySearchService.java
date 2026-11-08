package org.myweb.flowmat.domain.catalog.application;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.api.dto.response.EquipmentDayHistoryResponse;
import org.myweb.flowmat.domain.catalog.domain.entity.EquipmentDayHistory;
import org.myweb.flowmat.domain.catalog.repository.EquipmentDayHistorySearchRepository;
import org.myweb.flowmat.domain.catalog.repository.EquipmentRepository;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.history.HistoryPageResponse;
import org.myweb.flowmat.global.history.HistorySearchFilter;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** An equipment's date and downtime history beyond the latest 100 (docs/domain/equipment-schedule.md CV14), as the version history search. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EquipmentDayHistorySearchService {
    private final EquipmentDayHistorySearchRepository repository;
    private final EquipmentDayHistoryService lines;
    private final EquipmentRepository equipmentRepository;
    private final ProjectAccess access;

    public HistoryPageResponse<EquipmentDayHistoryResponse> search(String equipmentId, String kind, String action, String savedBy,
                                                                   String from, String to, String limit, String cursor) {
        var equipment = equipmentRepository.findByEquipmentIdAndDeletedYn(equipmentId, "N")
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        access.requireProjectReadAccess(equipment.getProjectId());
        if (kind != null && !kind.equals("day") && !kind.equals("downtime")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "kind must be day or downtime.");
        }
        HistorySearchFilter filter = HistorySearchFilter.parse(action, savedBy, from, to, limit, cursor);
        EquipmentDayHistory anchor = filter.cursor() == null ? null : repository.findByHistoryIdAndEquipmentId(filter.cursor(), equipmentId)
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "cursor must identify an entry in this equipment's history."));
        Specification<EquipmentDayHistory> scope = (root, query, cb) -> kind == null
            ? cb.equal(root.get("equipmentId"), equipmentId)
            : cb.and(cb.equal(root.get("equipmentId"), equipmentId), cb.equal(root.get("changeKind"), kind));
        var spec = scope.and(filter.specification(anchor == null ? null : anchor.getSavedAt(), anchor == null ? null : anchor.getHistoryId()));
        var rows = repository.findBy(spec,
            query -> query.sortBy(Sort.by(Sort.Order.desc("savedAt"), Sort.Order.desc("historyId"))).limit(filter.limit() + 1).all());
        boolean more = rows.size() > filter.limit();
        var page = more ? rows.subList(0, filter.limit()) : rows;
        String next = more ? page.get(page.size() - 1).getHistoryId() : null;
        return new HistoryPageResponse<>(page.stream().map(lines::response).toList(), next);
    }
}
