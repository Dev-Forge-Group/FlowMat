package org.myweb.flowmat.domain.workforce.application;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectMemberQuery;
import org.myweb.flowmat.domain.workforce.api.dto.response.WorkerDayHistoryResponse;
import org.myweb.flowmat.domain.workforce.domain.entity.WorkerDayHistory;
import org.myweb.flowmat.domain.workforce.repository.WorkerDayHistorySearchRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.history.HistoryPageResponse;
import org.myweb.flowmat.global.history.HistorySearchFilter;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A worker's date and day-off history beyond the latest 100 (docs/domain/worker-calendar.md WC67), as the version history search. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkerDayHistorySearchService {
    private final WorkerDayHistorySearchRepository repository;
    private final WorkerDayHistoryService lines;
    private final ProjectAccessService access;
    private final ProjectMemberQuery members;

    public HistoryPageResponse<WorkerDayHistoryResponse> search(String projectId, String userId, String kind, String action,
                                                                String savedBy, String from, String to, String limit, String cursor) {
        access.requireProjectReadAccess(projectId);
        if (!members.canRead(projectId, userId)) throw new BusinessException(ErrorCode.NOT_FOUND, "This worker is not in the project.");
        if (kind != null && !kind.equals("day") && !kind.equals("day_off")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "kind must be day or day_off.");
        }
        HistorySearchFilter filter = HistorySearchFilter.parse(action, savedBy, from, to, limit, cursor);
        WorkerDayHistory anchor = filter.cursor() == null ? null : repository
            .findByHistoryIdAndProjectIdAndUserId(filter.cursor(), projectId, userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "cursor must identify an entry in this worker's history."));
        Specification<WorkerDayHistory> scope = (root, query, cb) -> kind == null
            ? cb.and(cb.equal(root.get("projectId"), projectId), cb.equal(root.get("userId"), userId))
            : cb.and(cb.equal(root.get("projectId"), projectId), cb.equal(root.get("userId"), userId), cb.equal(root.get("changeKind"), kind));
        var spec = scope.and(filter.specification(anchor == null ? null : anchor.getSavedAt(), anchor == null ? null : anchor.getHistoryId()));
        var rows = repository.findBy(spec,
            query -> query.sortBy(Sort.by(Sort.Order.desc("savedAt"), Sort.Order.desc("historyId"))).limit(filter.limit() + 1).all());
        boolean more = rows.size() > filter.limit();
        var page = more ? rows.subList(0, filter.limit()) : rows;
        String next = more ? page.get(page.size() - 1).getHistoryId() : null;
        return new HistoryPageResponse<>(page.stream().map(lines::response).toList(), next);
    }
}
