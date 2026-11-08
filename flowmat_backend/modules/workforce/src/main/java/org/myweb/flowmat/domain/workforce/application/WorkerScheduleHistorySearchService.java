package org.myweb.flowmat.domain.workforce.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectMemberQuery;
import org.myweb.flowmat.domain.workforce.api.dto.response.WorkerScheduleHistoryResponse;
import org.myweb.flowmat.domain.workforce.domain.entity.WorkerScheduleHistory;
import org.myweb.flowmat.domain.workforce.repository.WorkerScheduleHistorySearchRepository;
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
public class WorkerScheduleHistorySearchService {
    private static final TypeReference<List<WorkerScheduleHistoryResponse.Shift>> SHIFTS = new TypeReference<>() {};
    private final WorkerScheduleHistorySearchRepository repository;
    private final ProjectAccess access;
    private final ProjectMemberQuery members;
    private final ObjectMapper mapper;

    public HistoryPageResponse<WorkerScheduleHistoryResponse> search(String projectId, String userId, String action, String savedBy,
                                                                     String from, String to, String limit, String cursor) {
        access.requireProjectReadAccess(projectId);
        if (!members.canRead(projectId, userId)) throw new BusinessException(ErrorCode.NOT_FOUND, "This worker is not in the project.");
        HistorySearchFilter filter = HistorySearchFilter.parse(action, savedBy, from, to, limit, cursor);
        WorkerScheduleHistory anchor = filter.cursor() == null ? null : repository
            .findByHistoryIdAndProjectIdAndUserId(filter.cursor(), projectId, userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "cursor must identify an entry in this worker's history."));
        Specification<WorkerScheduleHistory> scope = (root, query, cb) -> cb.and(
            cb.equal(root.get("projectId"), projectId), cb.equal(root.get("userId"), userId));
        var spec = scope.and(filter.specification(anchor == null ? null : anchor.getSavedAt(), anchor == null ? null : anchor.getHistoryId()));
        var rows = repository.findBy(spec,
            query -> query.sortBy(Sort.by(Sort.Order.desc("savedAt"), Sort.Order.desc("historyId"))).limit(filter.limit() + 1).all());
        boolean more = rows.size() > filter.limit();
        var page = more ? rows.subList(0, filter.limit()) : rows;
        String next = more ? page.get(page.size() - 1).getHistoryId() : null;
        return new HistoryPageResponse<>(page.stream().map(this::response).toList(), next);
    }

    private WorkerScheduleHistoryResponse response(WorkerScheduleHistory row) {
        try {
            return new WorkerScheduleHistoryResponse(row.getHistoryId(), row.getScheduleId(), row.getEffectiveFrom(), row.getAction(),
                row.getReason(), mapper.readValue(row.getShiftsJson(), SHIFTS), row.getRotaWeeks() == null ? null
                    : new WorkerScheduleHistoryResponse.Rota(row.getRotaWeeks(), row.getRotaStartsOn()), row.getSavedBy(), row.getSavedAt());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored schedule history is invalid.", exception);
        }
    }
}
