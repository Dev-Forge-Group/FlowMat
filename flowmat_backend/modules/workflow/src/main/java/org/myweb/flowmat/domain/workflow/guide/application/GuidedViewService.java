package org.myweb.flowmat.domain.workflow.guide.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.domain.workflow.application.WorkflowAccessService;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowAccessView;
import org.myweb.flowmat.domain.workflow.domain.entity.Process;
import org.myweb.flowmat.domain.workflow.guide.api.dto.request.GuidedViewOrderRequest;
import org.myweb.flowmat.domain.workflow.guide.api.dto.request.GuidedViewSaveRequest;
import org.myweb.flowmat.domain.workflow.guide.api.dto.response.GuidedViewResponse;
import org.myweb.flowmat.domain.workflow.guide.domain.GuidedView;
import org.myweb.flowmat.domain.workflow.guide.repository.GuidedViewRepository;
import org.myweb.flowmat.domain.workflow.repository.ProcessRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A workflow's guided views (docs/architecture/adr/ADR-037-guided-views.md): chapters that each show some of its
 * processes, read in order or played. Every change takes the workflow's row lock, so the count, the order and a
 * chapter's version are checked against what is really there.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class GuidedViewService {

    public static final int MAX_VIEWS = 8;
    public static final int MAX_PROCESSES = 30;
    private static final int MAX_LABEL = 48;
    private static final int MAX_NOTE = 140;
    private static final String NOT_DELETED = "N";

    private final GuidedViewRepository repository;
    private final ProcessRepository processRepository;
    private final WorkflowAccessService workflowAccess;
    private final ProjectAccess projectAccess;
    private final IdGenerator idGenerator;

    @Transactional(readOnly = true)
    public List<GuidedViewResponse> list(String workflowId) {
        workflowAccess.requireWorkflowReadAccess(workflowId);
        return read(workflowId);
    }

    /** The chapters as they are now, for a revision's snapshot: the caller has checked access. */
    @Transactional(readOnly = true)
    public List<GuidedViewResponse> snapshot(String workflowId) {
        return read(workflowId);
    }

    public GuidedViewResponse create(String workflowId, GuidedViewSaveRequest request) {
        WorkflowAccessView workflow = workflowAccess.requireWriteAndLock(workflowId);
        List<GuidedView> views = repository.findAllByWorkflowIdOrderBySortOrderAsc(workflowId);
        if (views.size() >= MAX_VIEWS) {
            throw badRequest("A workflow takes at most " + MAX_VIEWS + " chapters.");
        }
        GuidedView view = new GuidedView();
        view.setGuidedViewId(idGenerator.generate());
        view.setWorkflowId(workflowId);
        view.setProjectId(workflow.projectId());
        view.setSortOrder(views.size());
        apply(view, request, liveProcessIds(workflowId));
        String user = projectAccess.requireCurrentUserId();
        view.setCreatedBy(user);
        view.setUpdatedBy(user);
        return toResponse(repository.save(view), liveProcessIds(workflowId));
    }

    public GuidedViewResponse update(String workflowId, String guidedViewId, GuidedViewSaveRequest request) {
        workflowAccess.requireWriteAndLock(workflowId);
        GuidedView view = find(workflowId, guidedViewId);
        if (request.version() == null) {
            throw badRequest("Send the chapter's version with the change.");
        }
        if (request.version() != view.getVersion()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "Someone changed this chapter since it was read. Reload it and make the change again.");
        }
        Set<String> live = liveProcessIds(workflowId);
        apply(view, request, live);
        view.setVersion(view.getVersion() + 1);
        view.setUpdatedBy(projectAccess.requireCurrentUserId());
        return toResponse(view, live);
    }

    public void delete(String workflowId, String guidedViewId) {
        workflowAccess.requireWriteAndLock(workflowId);
        GuidedView view = find(workflowId, guidedViewId);
        repository.delete(view);
        repository.flush();
        List<GuidedView> rest = repository.findAllByWorkflowIdOrderBySortOrderAsc(workflowId);
        for (int index = 0; index < rest.size(); index++) {
            rest.get(index).setSortOrder(index);
        }
    }

    public List<GuidedViewResponse> reorder(String workflowId, GuidedViewOrderRequest request) {
        workflowAccess.requireWriteAndLock(workflowId);
        List<String> ids = request.guidedViewIds() == null ? List.of() : request.guidedViewIds();
        Map<String, GuidedView> views = repository.findAllByWorkflowIdOrderBySortOrderAsc(workflowId).stream()
            .collect(Collectors.toMap(GuidedView::getGuidedViewId, Function.identity()));
        if (ids.size() != views.size() || !views.keySet().equals(new HashSet<>(ids))) {
            throw badRequest("Send every chapter of the workflow once, in the new order.");
        }
        String user = projectAccess.requireCurrentUserId();
        for (int index = 0; index < ids.size(); index++) {
            GuidedView view = views.get(ids.get(index));
            if (view.getSortOrder() != index) {
                view.setSortOrder(index);
                view.setUpdatedBy(user);
            }
        }
        repository.flush();
        return read(workflowId);
    }

    private List<GuidedViewResponse> read(String workflowId) {
        Set<String> live = liveProcessIds(workflowId);
        return repository.findAllByWorkflowIdOrderBySortOrderAsc(workflowId).stream()
            .map(view -> toResponse(view, live))
            .toList();
    }

    private GuidedView find(String workflowId, String guidedViewId) {
        return repository.findById(guidedViewId)
            .filter(view -> workflowId.equals(view.getWorkflowId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private Set<String> liveProcessIds(String workflowId) {
        return processRepository.findAllByWorkflowIdAndDeletedYnOrderByCreatedAtAsc(workflowId, NOT_DELETED).stream()
            .map(Process::getProcessId)
            .collect(Collectors.toSet());
    }

    /** Checks and sets what a request may change: the name, the note and the processes, which must be live and distinct. */
    private static void apply(GuidedView view, GuidedViewSaveRequest request, Set<String> live) {
        String label = request.label() == null ? "" : request.label().trim();
        if (label.isEmpty() || label.codePointCount(0, label.length()) > MAX_LABEL) {
            throw badRequest("Name the chapter in 1 to " + MAX_LABEL + " characters.");
        }
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        if (note != null && note.codePointCount(0, note.length()) > MAX_NOTE) {
            throw badRequest("A chapter's note takes at most " + MAX_NOTE + " characters.");
        }
        List<String> focus = request.focusProcessIds() == null ? List.of() : request.focusProcessIds();
        if (focus.isEmpty() || focus.size() > MAX_PROCESSES) {
            throw badRequest("A chapter shows 1 to " + MAX_PROCESSES + " processes.");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String processId : focus) {
            if (processId == null || !live.contains(processId)) {
                throw badRequest("Process " + processId + " is not in this workflow.");
            }
            if (!seen.add(processId)) {
                throw badRequest("Process " + processId + " is in the chapter twice.");
            }
        }
        view.setLabel(label);
        view.setNote(note);
        view.setFocusProcessIds(new ArrayList<>(seen));
    }

    private static GuidedViewResponse toResponse(GuidedView view, Set<String> live) {
        // A process deleted since the chapter was saved is left out; the stored list is tidied on the next save.
        List<String> focus = view.getFocusProcessIds().stream().filter(live::contains).toList();
        return new GuidedViewResponse(view.getGuidedViewId(), view.getWorkflowId(), view.getSortOrder(), view.getLabel(),
            view.getNote(), focus, view.getVersion());
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
