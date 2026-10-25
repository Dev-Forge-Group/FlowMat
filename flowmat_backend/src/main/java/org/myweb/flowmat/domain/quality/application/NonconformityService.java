package org.myweb.flowmat.domain.quality.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.inventory.application.publicapi.LotQuery;
import org.myweb.flowmat.domain.inventory.application.publicapi.LotView;
import org.myweb.flowmat.domain.notification.application.publicapi.NotificationCommand;
import org.myweb.flowmat.domain.production.application.publicapi.ProductionRunQuery;
import org.myweb.flowmat.domain.production.application.publicapi.ProductionRunView;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectMemberQuery;
import org.myweb.flowmat.domain.quality.api.dto.request.CorrectiveActionRequest;
import org.myweb.flowmat.domain.quality.api.dto.request.NonconformityCreateRequest;
import org.myweb.flowmat.domain.quality.api.dto.request.NonconformityUpdateRequest;
import org.myweb.flowmat.domain.quality.api.dto.response.NonconformityResponse;
import org.myweb.flowmat.domain.quality.domain.entity.CorrectiveAction;
import org.myweb.flowmat.domain.quality.domain.entity.DefectLog;
import org.myweb.flowmat.domain.quality.domain.entity.Nonconformity;
import org.myweb.flowmat.domain.quality.domain.entity.NonconformityDefect;
import org.myweb.flowmat.domain.quality.repository.CorrectiveActionRepository;
import org.myweb.flowmat.domain.quality.repository.DefectLogRepository;
import org.myweb.flowmat.domain.quality.repository.NonconformityDefectRepository;
import org.myweb.flowmat.domain.quality.repository.NonconformityRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nonconformities and their corrective actions (docs/domain/nonconformity.md, benchmark FM-MFG-004). A nonconformity
 * report gathers defects, records the root cause and what happens to the product (disposition), and is closed once
 * its actions are done. Records only: scrapping stock stays with resolving a defect.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NonconformityService {

    private static final List<String> SEVERITIES = List.of("minor", "major", "critical");
    private static final Set<String> DISPOSITIONS = Set.of("pending", "use_as_is", "rework", "scrap", "return_to_supplier");
    private static final Set<String> ACTION_TYPES = Set.of("correction", "corrective", "preventive");
    private static final Set<String> VERIFICATION_RESULTS = Set.of("effective", "not_effective");
    private static final String OPEN = "open";
    private static final String NOT_DELETED = "N";

    private final NonconformityRepository nonconformityRepository;
    private final org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery projectCalendar;
    private final NonconformityDefectRepository linkRepository;
    private final CorrectiveActionRepository actionRepository;
    private final DefectLogRepository defectLogRepository;
    private final CatalogQuery catalogQuery;
    private final LotQuery lotQuery;
    private final ProductionRunQuery productionRunQuery;
    private final ProjectMemberQuery projectMemberQuery;
    private final ProjectAccessService projectAccessService;
    private final IdGenerator idGenerator;
    private final NotificationCommand notifications;

    /** Newest first; only the given status (open, closed or cancelled) when asked. */
    public List<NonconformityResponse> list(String projectId, String status) {
        String project = required(projectId, "projectId");
        projectAccessService.requireProjectReadAccess(project);
        String filter = trimToNull(status);
        return responses(nonconformityRepository.findAllByProjectIdOrderByRaisedAtDesc(project).stream()
            .filter(ncr -> filter == null || filter.equals(ncr.getStatus()))
            .toList());
    }

    public NonconformityResponse get(String nonconformityId) {
        Nonconformity ncr = nonconformityRepository.findById(required(nonconformityId, "nonconformityId"))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        projectAccessService.requireProjectReadAccess(ncr.getProjectId());
        return responses(List.of(ncr)).get(0);
    }

    @Transactional
    public NonconformityResponse create(NonconformityCreateRequest request) {
        String projectId = required(request.projectId(), "projectId");
        projectAccessService.requireProjectWriteAccess(projectId);
        String actor = projectAccessService.requireCurrentUserId();
        List<DefectLog> defects = linkable(projectId, request.defectLogIds());
        DefectLog first = defects.isEmpty() ? null : defects.get(0);
        String itemId = trimToNull(request.itemId()) != null ? request.itemId().trim() : first == null ? null : first.getItemId();
        String lotId = trimToNull(request.lotId()) != null ? request.lotId().trim() : first == null ? null : first.getLotId();
        String runId = trimToNull(request.productionRunId()) != null ? request.productionRunId().trim()
            : first == null ? null : first.getProductionRunId();
        requireSubject(projectId, itemId, lotId, runId);
        String severity = trimToNull(request.severity()) != null ? severity(request.severity())
            : defects.stream().map(DefectLog::getSeverity).filter(SEVERITIES::contains)
                .max(Comparator.comparingInt(SEVERITIES::indexOf)).orElse("minor");

        // Numbers are per project and never reused; two raised at once take turns.
        nonconformityRepository.lockKey("nonconformity|" + projectId);
        Nonconformity ncr = new Nonconformity();
        ncr.setNonconformityId(idGenerator.generate());
        ncr.setProjectId(projectId);
        ncr.setNcrNo(String.format(Locale.ROOT, "NCR-%04d", nonconformityRepository.countByProjectId(projectId) + 1));
        ncr.setTitle(required(request.title(), "title"));
        ncr.setDescription(trimToNull(request.description()));
        ncr.setSeverity(severity);
        ncr.setStatus(OPEN);
        ncr.setItemId(itemId);
        ncr.setLotId(lotId);
        ncr.setProductionRunId(runId);
        ncr.setDisposition("pending");
        ncr.setRaisedBy(actor);
        ncr.setRaisedAt(OffsetDateTime.now());
        Nonconformity saved = nonconformityRepository.saveAndFlush(ncr);
        link(saved, defects);
        return responses(List.of(saved)).get(0);
    }

    @Transactional
    public NonconformityResponse update(String nonconformityId, NonconformityUpdateRequest request) {
        Nonconformity ncr = lockOpen(nonconformityId);
        if (request.title() != null) {
            ncr.setTitle(required(request.title(), "title"));
        }
        if (request.description() != null) {
            ncr.setDescription(trimToNull(request.description()));
        }
        if (request.severity() != null) {
            ncr.setSeverity(severity(request.severity()));
        }
        if (request.rootCause() != null) {
            ncr.setRootCause(trimToNull(request.rootCause()));
        }
        if (request.disposition() != null) {
            String disposition = request.disposition().trim().toLowerCase(Locale.ROOT);
            if (!DISPOSITIONS.contains(disposition)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "disposition must be pending, use_as_is, rework, scrap or return_to_supplier.");
            }
            ncr.setDisposition(disposition);
        }
        return responses(List.of(nonconformityRepository.saveAndFlush(ncr))).get(0);
    }

    @Transactional
    public NonconformityResponse addDefects(String nonconformityId, List<String> defectLogIds) {
        Nonconformity ncr = lockOpen(nonconformityId);
        link(ncr, linkable(ncr.getProjectId(), defectLogIds));
        return responses(List.of(ncr)).get(0);
    }

    @Transactional
    public NonconformityResponse addAction(String nonconformityId, CorrectiveActionRequest request) {
        Nonconformity ncr = lockOpen(nonconformityId);
        String type = request.actionType() == null ? "" : request.actionType().trim().toLowerCase(Locale.ROOT);
        if (!ACTION_TYPES.contains(type)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "actionType must be correction, corrective or preventive.");
        }
        String owner = trimToNull(request.ownerId());
        if (owner != null && !isMember(ncr.getProjectId(), owner)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, owner + " is not a member of this project.");
        }
        CorrectiveAction action = new CorrectiveAction();
        action.setCorrectiveActionId(idGenerator.generate());
        action.setNonconformityId(ncr.getNonconformityId());
        action.setActionNo(actionRepository.findAllByNonconformityIdOrderByActionNoAsc(ncr.getNonconformityId()).size() + 1);
        action.setActionType(type);
        action.setDescription(required(request.description(), "description"));
        action.setOwnerId(owner);
        action.setDueDate(request.dueDate());
        action.setStatus(OPEN);
        action.setCreatedBy(projectAccessService.requireCurrentUserId());
        action.setCreatedAt(OffsetDateTime.now());
        actionRepository.saveAndFlush(action);
        // The owner hears of it (docs/domain/notification.md NT2), unless they gave it to themselves.
        notifications.notifyAssignee(owner, action.getCreatedBy(), ncr.getProjectId(), "ncr_action",
            "Action " + action.getActionNo() + " of " + ncr.getNcrNo() + " is yours",
            action.getDescription() + (action.getDueDate() == null ? "" : " · due " + action.getDueDate()), "nonconformity",
            ncr.getNonconformityId());
        return responses(List.of(ncr)).get(0);
    }

    /** Marks an open action done (with what was done) or cancelled (with why). */
    @Transactional
    public NonconformityResponse finishAction(String nonconformityId, String correctiveActionId, boolean done, String note) {
        Nonconformity ncr = lockOpen(nonconformityId);
        CorrectiveAction action = actionRepository
            .findByCorrectiveActionIdAndNonconformityId(required(correctiveActionId, "correctiveActionId"), ncr.getNonconformityId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!OPEN.equals(action.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Action " + action.getActionNo() + " is already " + action.getStatus() + ".");
        }
        String text = trimToNull(note);
        if (text == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, done ? "Say what was done." : "Say why the action is cancelled.");
        }
        action.setStatus(done ? "done" : "cancelled");
        action.setResultNote(text);
        action.setFinishedBy(projectAccessService.requireCurrentUserId());
        action.setFinishedAt(OffsetDateTime.now());
        actionRepository.saveAndFlush(action);
        return responses(List.of(ncr)).get(0);
    }

    /**
     * Closes a nonconformity whose root cause and disposition are recorded, with at least one action done and none
     * still open. Everything missing is named at once. A check date says when its actions are due to be checked (N13).
     */
    @Transactional
    public NonconformityResponse close(String nonconformityId, String note, LocalDate verificationDueDate) {
        Nonconformity ncr = lockOpen(nonconformityId);
        List<CorrectiveAction> actions = actionRepository.findAllByNonconformityIdOrderByActionNoAsc(ncr.getNonconformityId());
        List<String> missing = new ArrayList<>();
        if (ncr.getRootCause() == null) {
            missing.add("record the root cause");
        }
        if ("pending".equals(ncr.getDisposition())) {
            missing.add("decide the disposition");
        }
        long open = actions.stream().filter(action -> OPEN.equals(action.getStatus())).count();
        if (open > 0) {
            missing.add("finish or cancel " + open + " open " + (open == 1 ? "action" : "actions"));
        }
        if (actions.stream().noneMatch(action -> "done".equals(action.getStatus()))) {
            missing.add("complete at least one action");
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Before closing " + ncr.getNcrNo() + ", " + String.join(", ", missing) + ".");
        }
        requireCheckDate(ncr, verificationDueDate);
        finish(ncr, "closed", trimToNull(note));
        ncr.setVerificationDueDate(verificationDueDate);
        return responses(List.of(nonconformityRepository.saveAndFlush(ncr))).get(0);
    }

    /** Moves or clears the day a closed nonconformity's actions are due to be checked (N13), until the check is recorded. */
    @Transactional
    public NonconformityResponse setVerificationDue(String nonconformityId, LocalDate verificationDueDate) {
        Nonconformity ncr = lockClosedUnchecked(nonconformityId);
        requireCheckDate(ncr, verificationDueDate);
        ncr.setVerificationDueDate(verificationDueDate);
        return responses(List.of(nonconformityRepository.saveAndFlush(ncr))).get(0);
    }

    /**
     * Records whether a closed nonconformity's actions worked (N12), once. When they did not, the note says what still goes
     * wrong; the nonconformity stays closed and a follow-up is raised for what is left.
     */
    @Transactional
    public NonconformityResponse verify(String nonconformityId, String result, String note) {
        Nonconformity ncr = lockClosedUnchecked(nonconformityId);
        String value = trimToNull(result) == null ? null : result.trim().toLowerCase(Locale.ROOT);
        if (value == null || !VERIFICATION_RESULTS.contains(value)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "result must be effective or not_effective.");
        }
        String text = trimToNull(note);
        if ("not_effective".equals(value) && text == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Say what still goes wrong when the actions did not work.");
        }
        ncr.setVerificationResult(value);
        ncr.setVerificationNote(text);
        ncr.setVerifiedBy(projectAccessService.requireCurrentUserId());
        ncr.setVerifiedAt(OffsetDateTime.now());
        return responses(List.of(nonconformityRepository.saveAndFlush(ncr))).get(0);
    }

    /** Cancels a nonconformity raised by mistake: open actions are cancelled and its defects are freed. */
    @Transactional
    public NonconformityResponse cancel(String nonconformityId, String note) {
        Nonconformity ncr = lockOpen(nonconformityId);
        String reason = trimToNull(note);
        if (reason == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Say why the nonconformity is cancelled.");
        }
        String actor = projectAccessService.requireCurrentUserId();
        for (CorrectiveAction action : actionRepository.findAllByNonconformityIdOrderByActionNoAsc(ncr.getNonconformityId())) {
            if (OPEN.equals(action.getStatus())) {
                action.setStatus("cancelled");
                action.setResultNote("Nonconformity cancelled.");
                action.setFinishedBy(actor);
                action.setFinishedAt(OffsetDateTime.now());
            }
        }
        linkRepository.deleteAll(linkRepository.findAllByNonconformityId(ncr.getNonconformityId()));
        finish(ncr, "cancelled", reason);
        return responses(List.of(nonconformityRepository.saveAndFlush(ncr))).get(0);
    }

    private void finish(Nonconformity ncr, String status, String note) {
        ncr.setStatus(status);
        ncr.setClosedBy(projectAccessService.requireCurrentUserId());
        ncr.setClosedAt(OffsetDateTime.now());
        ncr.setClosureNote(note);
    }

    /** A closed nonconformity whose actions were not checked yet, locked for the change. */
    private Nonconformity lockClosedUnchecked(String nonconformityId) {
        Nonconformity ncr = nonconformityRepository.findForUpdate(required(nonconformityId, "nonconformityId"))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        projectAccessService.requireProjectWriteAccess(ncr.getProjectId());
        if (!"closed".equals(ncr.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                ncr.getNcrNo() + " is " + ncr.getStatus() + "; only a closed nonconformity's actions can be checked.");
        }
        if (ncr.getVerifiedAt() != null) {
            throw new BusinessException(ErrorCode.CONFLICT, ncr.getNcrNo() + " was already checked by " + ncr.getVerifiedBy() + ".");
        }
        return ncr;
    }

    /** A check date, when given, is the project's today or later. */
    private void requireCheckDate(Nonconformity ncr, LocalDate day) {
        if (day == null) {
            return;
        }
        LocalDate today = projectCalendar.today(ncr.getProjectId());
        if (day.isBefore(today)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "The check date " + day + " is before today (" + today + ").");
        }
    }

    private Nonconformity lockOpen(String nonconformityId) {
        Nonconformity ncr = nonconformityRepository.findForUpdate(required(nonconformityId, "nonconformityId"))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        projectAccessService.requireProjectWriteAccess(ncr.getProjectId());
        if (!OPEN.equals(ncr.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, ncr.getNcrNo() + " is " + ncr.getStatus() + ".");
        }
        return ncr;
    }

    /** The defects, in the given order, each in the project and not on a nonconformity yet. */
    private List<DefectLog> linkable(String projectId, List<String> defectLogIds) {
        List<DefectLog> defects = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (String id : defectLogIds == null ? List.<String>of() : defectLogIds) {
            if (trimToNull(id) != null) {
                ids.add(id.trim());
            }
        }
        for (String id : ids) {
            DefectLog defect = defectLogRepository.findById(id)
                .filter(found -> projectId.equals(found.getProjectId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "The defect was not found in this project."));
            linkRepository.findByDefectLogId(id).ifPresent(link -> {
                String ncrNo = nonconformityRepository.findById(link.getNonconformityId()).map(Nonconformity::getNcrNo).orElse("another nonconformity");
                throw new BusinessException(ErrorCode.CONFLICT, "The " + defect.getDefectType() + " defect is already on " + ncrNo + ".");
            });
            defects.add(defect);
        }
        return defects;
    }

    private void link(Nonconformity ncr, List<DefectLog> defects) {
        for (DefectLog defect : defects) {
            NonconformityDefect link = new NonconformityDefect();
            link.setNonconformityDefectId(idGenerator.generate());
            link.setNonconformityId(ncr.getNonconformityId());
            link.setDefectLogId(defect.getDefectLogId());
            linkRepository.save(link);
        }
        linkRepository.flush();
    }

    private void requireSubject(String projectId, String itemId, String lotId, String runId) {
        if (itemId != null && catalogQuery.findProjectItem(projectId, itemId).isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "The item was not found in this project.");
        }
        if (lotId != null) {
            LotView lot = lotQuery.findProjectLot(projectId, lotId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "The LOT was not found in this project."));
            if (itemId != null && !itemId.equals(lot.itemId())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "LOT " + lot.lotNo() + " is a LOT of a different item.");
            }
        }
        if (runId != null && productionRunQuery.findProjectRun(projectId, runId).isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "The run was not found in this project.");
        }
    }

    /** The owner or an active member; asked as a yes or no so a refusal does not pass through a transactional proxy. */
    private boolean isMember(String projectId, String userId) {
        projectAccessService.requireProjectReadAccess(projectId);
        return userId.equals(projectMemberQuery.ownerOf(projectId)) || projectMemberQuery.isActiveMember(projectId, userId);
    }

    private List<NonconformityResponse> responses(List<Nonconformity> ncrs) {
        if (ncrs.isEmpty()) {
            return List.of();
        }
        List<String> ids = ncrs.stream().map(Nonconformity::getNonconformityId).toList();
        Map<String, List<NonconformityDefect>> links = linkRepository.findAllByNonconformityIdIn(ids).stream()
            .collect(Collectors.groupingBy(NonconformityDefect::getNonconformityId));
        Map<String, DefectLog> defects = byId(defectLogRepository.findAllById(
            links.values().stream().flatMap(List::stream).map(NonconformityDefect::getDefectLogId).toList()), DefectLog::getDefectLogId);
        Map<String, List<CorrectiveAction>> actions = actionRepository.findAllByNonconformityIdInOrderByActionNoAsc(ids).stream()
            .collect(Collectors.groupingBy(CorrectiveAction::getNonconformityId));
        Map<String, CatalogItemView> items = catalogQuery.findItems(distinct(Stream.concat(
            ncrs.stream().map(Nonconformity::getItemId), defects.values().stream().map(DefectLog::getItemId))));
        Map<String, LotView> lots = lotQuery.findLots(distinct(Stream.concat(
            ncrs.stream().map(Nonconformity::getLotId), defects.values().stream().map(DefectLog::getLotId))));
        Map<String, ProductionRunView> runs = productionRunQuery.findRuns(distinct(ncrs.stream().map(Nonconformity::getProductionRunId)));
        Map<String, LocalDate> businessDates = ncrs.stream().map(Nonconformity::getProjectId).distinct()
            .collect(Collectors.toMap(Function.identity(), projectCalendar::today));
        return ncrs.stream().map(ncr -> {
            LocalDate today = businessDates.get(ncr.getProjectId());
            List<NonconformityResponse.LinkedDefect> linked = links.getOrDefault(ncr.getNonconformityId(), List.of()).stream()
                .map(link -> defects.get(link.getDefectLogId()))
                .filter(Objects::nonNull)
                .map(defect -> new NonconformityResponse.LinkedDefect(
                    defect.getDefectLogId(), defect.getDefectType(), defect.getSeverity(), defect.getQuantity(),
                    code(items.get(defect.getItemId())), lotNo(lots.get(defect.getLotId())), "Y".equals(defect.getResolvedYn())))
                .toList();
            List<NonconformityResponse.Action> actionList = actions.getOrDefault(ncr.getNonconformityId(), List.of()).stream()
                .map(action -> new NonconformityResponse.Action(
                    action.getCorrectiveActionId(), action.getActionNo(), action.getActionType(), action.getDescription(),
                    action.getOwnerId(), action.getDueDate(), action.getStatus(), action.getResultNote(), action.getCreatedBy(),
                    action.getCreatedAt(), action.getFinishedBy(), action.getFinishedAt(),
                    OPEN.equals(action.getStatus()) && action.getDueDate() != null && action.getDueDate().isBefore(today)))
                .toList();
            ProductionRunView run = runs.get(ncr.getProductionRunId());
            return new NonconformityResponse(
                ncr.getNonconformityId(), ncr.getProjectId(), ncr.getNcrNo(), ncr.getTitle(), ncr.getDescription(),
                ncr.getSeverity(), ncr.getStatus(), ncr.getItemId(), code(items.get(ncr.getItemId())), ncr.getLotId(),
                lotNo(lots.get(ncr.getLotId())), ncr.getProductionRunId(), run == null ? null : run.runNumber(),
                ncr.getRootCause(), ncr.getDisposition(), ncr.getRaisedBy(), ncr.getRaisedAt(), ncr.getClosedBy(),
                ncr.getClosedAt(), ncr.getClosureNote(), ncr.getVerificationResult(), ncr.getVerificationNote(), ncr.getVerifiedBy(),
                ncr.getVerifiedAt(), ncr.getVerificationDueDate(),
                "closed".equals(ncr.getStatus()) && ncr.getVerifiedAt() == null && ncr.getVerificationDueDate() != null
                    && ncr.getVerificationDueDate().isBefore(today),
                linked, actionList,
                (int) actionList.stream().filter(action -> OPEN.equals(action.status())).count(),
                (int) actionList.stream().filter(NonconformityResponse.Action::overdue).count());
        }).toList();
    }

    private static String severity(String value) {
        String severity = value.trim().toLowerCase(Locale.ROOT);
        if (!SEVERITIES.contains(severity)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "severity must be minor, major or critical.");
        }
        return severity;
    }

    private static <T> Map<String, T> byId(Iterable<T> found, Function<T, String> id) {
        return StreamSupport.stream(found.spliterator(), false).collect(Collectors.toMap(id, Function.identity(), (a, b) -> a));
    }

    private static Collection<String> distinct(Stream<String> ids) {
        return ids.filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private static String code(CatalogItemView item) {
        return item == null ? null : item.itemCode();
    }

    private static String lotNo(LotView lot) {
        return lot == null ? null : lot.lotNo();
    }

    private static String required(String value, String field) {
        String text = trimToNull(value);
        if (text == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, field + " is required.");
        }
        return text;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
