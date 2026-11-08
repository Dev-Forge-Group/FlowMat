package org.myweb.flowmat.domain.production.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.flowrun.application.publicapi.LinkedRunStep;
import org.myweb.flowmat.domain.flowrun.application.publicapi.LinkedRunSteps;
import org.myweb.flowmat.domain.production.api.dto.request.RunLaborCancelRequest;
import org.myweb.flowmat.domain.production.api.dto.request.RunLaborRequest;
import org.myweb.flowmat.domain.production.api.dto.response.RunLaborResponse;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRunLabor;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrderCrew;
import org.myweb.flowmat.domain.production.repository.ProductionRunLaborRepository;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.domain.production.repository.WorkOrderCrewRepository;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectMemberQuery;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerCalendarQuery;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerTime;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who worked on a run and for how long (docs/domain/worker-calendar.md LB1-LB5, docs/architecture/adr/ADR-019-run-labor.md).
 * Like equipment running time (docs/domain/equipment-setup-cost.md ER1-ER4) it is recorded on open runs, kept apart from
 * material cost and cancelled rather than deleted; there are no worker rates, so it has no cost. The crew's shifts during the
 * run suggest how much to record. A record can name a process step of the run (LB13-LB15) and carry its clock times (LB31).
 * A finished run's worked time changes only through a run correction (LB9-LB10).
 */
@Service
@RequiredArgsConstructor
public class RunLaborService {

    private static final int MAX_MINUTES = 10_080;
    private static final int MAX_TEXT = 500;
    /** Worked time with clock times lies within a day, as a stretch of attendance does (LB31). */
    private static final Duration LONGEST_TIMED = Duration.ofHours(24);
    /** Clocks of phones and terminals run a little apart. */
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final String NOT_DELETED = "N";
    private static final BigDecimal SECONDS_OF_A_FULL_SHARE = BigDecimal.valueOf(60L * WorkOrderCrewService.FULL_SHARE);

    private final ProductionRunRepository runs;
    private final WorkOrderCrewRepository crew;
    private final ProductionRunLaborRepository labor;
    private final ProjectMemberQuery members;
    private final WorkerCalendarQuery workerCalendars;
    private final LinkedRunSteps linkedSteps;
    private final ProjectAccess access;
    private final IdGenerator ids;

    @Transactional(readOnly = true)
    public RunLaborResponse labor(String runId) {
        ProductionRun run = runs.findByProductionRunIdAndDeletedYn(runId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        access.requireProjectReadAccess(run.getProjectId());
        return response(run);
    }

    /** Records worked time under the run's lock, so it cannot slip past the run finishing (LB2). */
    @Transactional
    public RunLaborResponse record(String runId, RunLaborRequest request) {
        ProductionRun run = runs.findForUpdate(runId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        access.requireProjectWriteAccess(run.getProjectId());
        if (request == null || request.requestId() == null) {
            throw bad("requestId is required.");
        }
        // Clock times are optional; with them the minutes may be left out, and are at most the time between (LB31).
        OffsetDateTime workedFrom = time(request.workedFrom(), "workedFrom");
        OffsetDateTime workedTo = time(request.workedTo(), "workedTo");
        if ((workedFrom == null) != (workedTo == null)) {
            throw bad("Give both workedFrom and workedTo, or neither.");
        }
        Integer minutes = request.workedMinutes();
        if (workedFrom != null) {
            long between = between(workedFrom, workedTo);
            if (minutes == null) {
                minutes = (int) between;
            } else if (minutes > between) {
                throw bad("workedMinutes cannot be more than the " + between + " minutes from workedFrom to workedTo.");
            }
        }
        if (minutes == null || minutes < 1 || minutes > MAX_MINUTES) {
            throw bad("workedMinutes must be whole minutes from 1 to " + MAX_MINUTES + ".");
        }
        String note = text(request.note(), "note");
        String actor = access.requireCurrentUserId();
        String requested = ProductionText.trimToNull(request.userId(), "userId");
        String userId = requested != null ? requested : actor;
        String stepId = ProductionText.trimToNull(request.stepId(), "stepId");

        ProductionRunLabor previous = labor.findByProductionRunIdAndRequestId(runId, request.requestId()).orElse(null);
        if (previous != null) {
            // The same author sending the same record again after a lost reply gets the run as it is now.
            boolean same = actor.equals(previous.getRecordedBy()) && userId.equals(previous.getUserId())
                && minutes.equals(previous.getWorkedMinutes()) && Objects.equals(note, previous.getNote())
                && Objects.equals(stepId, previous.getStepId()) && sameTime(workedFrom, previous.getWorkedFrom())
                && sameTime(workedTo, previous.getWorkedTo());
            if (!same) {
                throw new BusinessException(ErrorCode.CONFLICT, "requestId already belongs to different worked time.");
            }
            return response(run);
        }
        ProductionRunServiceImpl.requireOpenRun(run);
        if (!members.canRead(run.getProjectId(), userId)) {
            throw bad(userId + " is not in this project.");
        }
        if (stepId != null && processSteps(runId).stream().noneMatch(step -> step.stepId().equals(stepId))) {
            throw bad("stepId is not a process step of this run.");
        }
        if (workedTo != null && run.getCreatedAt() != null && !workedTo.isAfter(run.getCreatedAt())) {
            throw bad("The worked time ends before " + run.getRunNumber() + " started at " + run.getCreatedAt() + ".");
        }

        ProductionRunLabor row = new ProductionRunLabor();
        row.setRunLaborId(ids.generate());
        row.setProductionRunId(runId);
        row.setProjectId(run.getProjectId());
        row.setUserId(userId);
        row.setWorkedMinutes(minutes);
        row.setRequestId(request.requestId());
        row.setNote(note);
        row.setStepId(stepId);
        row.setWorkedFrom(workedFrom);
        row.setWorkedTo(workedTo);
        row.setRecordedBy(actor);
        row.setRecordedAt(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
        row.setCancelledYn("N");
        labor.saveAndFlush(row);
        return response(run);
    }

    /** Mistaken worked time stops counting but stays on record; cancelling it again changes nothing (LB3). */
    @Transactional
    public RunLaborResponse cancel(String runId, String runLaborId, RunLaborCancelRequest request) {
        ProductionRun run = runs.findForUpdate(runId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        access.requireProjectWriteAccess(run.getProjectId());
        ProductionRunLabor row = labor.findById(runLaborId)
            .filter(one -> runId.equals(one.getProductionRunId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (row.isCancelled()) {
            return response(run);
        }
        ProductionRunServiceImpl.requireOpenRun(run);
        String reason = text(request == null ? null : request.reason(), "reason");
        if (reason == null) {
            throw bad("Give a reason when cancelling worked time.");
        }
        row.setCancelledYn("Y");
        row.setCancelledBy(access.requireCurrentUserId());
        row.setCancelledAt(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
        row.setCancelReason(reason);
        labor.saveAndFlush(row);
        return response(run);
    }

    // ---- corrections of finished runs (LB9-LB10): the correction service validates and applies, under the run's lock ----

    /** Worked time of the run a correction can cancel: not cancelled already. */
    ProductionRunLabor findCancellable(ProductionRun run, String runLaborId) {
        ProductionText.requireStorable(runLaborId, "targetRunLaborId");
        if (runLaborId == null || runLaborId.isBlank()) {
            throw bad("Choose the worked time to cancel.");
        }
        ProductionRunLabor row = labor.findById(runLaborId.trim())
            .filter(one -> run.getProductionRunId().equals(one.getProductionRunId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (row.isCancelled()) {
            throw new BusinessException(ErrorCode.CONFLICT, "This worked time was already cancelled by " + row.getCancelledBy() + ".");
        }
        return row;
    }

    /** Who worked in the time a correction would add, checked like a live record (LB2, LB9); a correction names them. */
    String requireCorrectionLabor(ProductionRun run, String userId, Integer minutes) {
        if (minutes == null || minutes < 1 || minutes > MAX_MINUTES) {
            throw bad("workedMinutes must be whole minutes from 1 to " + MAX_MINUTES + ".");
        }
        String id = ProductionText.trimToNull(userId, "userId");
        if (id == null) {
            throw bad("Choose who worked in the time to add.");
        }
        if (!members.canRead(run.getProjectId(), id)) {
            throw bad(id + " is not in this project.");
        }
        return id;
    }

    /** The process step a correction's added time names, checked like a live record's (LB13, LB17); null for the whole run. */
    String requireCorrectionStep(ProductionRun run, String stepId) {
        String id = ProductionText.trimToNull(stepId, "stepId");
        if (id != null && processSteps(run.getProductionRunId()).stream().noneMatch(step -> step.stepId().equals(id))) {
            throw bad("stepId is not a process step of this run.");
        }
        return id;
    }

    void cancelForCorrection(ProductionRun run, String runLaborId, String reason, String actor) {
        ProductionRunLabor row = findCancellable(run, runLaborId);
        row.setCancelledYn("Y");
        row.setCancelledBy(actor);
        row.setCancelledAt(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
        row.setCancelReason(clip(reason));
        labor.saveAndFlush(row);
    }

    /** Worked time a correction adds, recorded by whoever approved it, with the correction as its note (LB10). */
    ProductionRunLabor addForCorrection(ProductionRun run, String correctionLineId, String userId, int minutes, String stepId,
                                        String note, String actor) {
        ProductionRunLabor row = new ProductionRunLabor();
        row.setRunLaborId(ids.generate());
        row.setProductionRunId(run.getProductionRunId());
        row.setProjectId(run.getProjectId());
        row.setUserId(userId);
        row.setWorkedMinutes(minutes);
        row.setStepId(stepId);
        // The correction line is the key, so the same line never adds worked time twice.
        row.setRequestId(UUID.nameUUIDFromBytes(("run-correction-line:" + correctionLineId).getBytes(StandardCharsets.UTF_8)));
        row.setNote(clip(note));
        row.setRecordedBy(actor);
        row.setRecordedAt(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
        row.setCancelledYn("N");
        return labor.saveAndFlush(row);
    }

    private static String clip(String text) {
        return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
    }

    private RunLaborResponse response(ProductionRun run) {
        List<LinkedRunStep> steps = processSteps(run.getProductionRunId());
        Map<String, String> stepNames = new HashMap<>();
        steps.forEach(step -> stepNames.put(step.stepId(), step.nodeName()));
        Map<String, Integer> worked = new LinkedHashMap<>();
        Map<String, Integer> workedOnSteps = new HashMap<>();
        int total = 0;
        List<RunLaborResponse.Line> lines = new ArrayList<>();
        for (ProductionRunLabor row : labor.findAllByProductionRunIdOrderByRecordedAtAscRunLaborIdAsc(run.getProductionRunId())) {
            worked.putIfAbsent(row.getUserId(), 0);
            if (!row.isCancelled()) {
                total += row.getWorkedMinutes();
                worked.merge(row.getUserId(), row.getWorkedMinutes(), Integer::sum);
                if (row.getStepId() != null) {
                    workedOnSteps.merge(row.getStepId(), row.getWorkedMinutes(), Integer::sum);
                }
            }
            lines.add(new RunLaborResponse.Line(row.getRunLaborId(), row.getUserId(), row.getWorkedMinutes(), row.getNote(),
                row.getRecordedBy(), row.getRecordedAt(), row.isCancelled(), row.getCancelledBy(), row.getCancelledAt(),
                row.getCancelReason(), row.getStepId(), row.getStepId() == null ? null : stepNames.get(row.getStepId()),
                row.getWorkedFrom(), row.getWorkedTo()));
        }
        List<WorkOrderCrew> team = run.getWorkOrderId() == null ? List.of()
            : crew.findAllByWorkOrderIdOrderByAddedAtAscCrewIdAsc(run.getWorkOrderId());
        Map<String, Suggestion> suggested = suggestions(run, team, worked);
        List<RunLaborResponse.Person> people = new ArrayList<>();
        for (WorkOrderCrew member : team) {
            Suggestion one = suggested.get(member.getUserId());
            people.add(new RunLaborResponse.Person(member.getUserId(), WorkOrderCrewService.shareOf(member),
                worked.getOrDefault(member.getUserId(), 0), one == null ? null : one.minutes(), one == null ? null : one.from()));
        }
        worked.forEach((userId, minutes) -> {
            if (team.stream().noneMatch(member -> member.getUserId().equals(userId))) {
                people.add(new RunLaborResponse.Person(userId, null, minutes, null, null));
            }
        });
        List<RunLaborResponse.Step> stepViews = steps.stream()
            .map(step -> new RunLaborResponse.Step(step.stepId(), step.nodeName(), step.sequenceNo(), step.status(), step.startedAt(),
                step.endedAt(), workedOnSteps.getOrDefault(step.stepId(), 0)))
            .toList();
        return new RunLaborResponse(run.getProductionRunId(), total, people, lines, stepViews);
    }

    /** The run's process steps (ADR-011), gateways left out; none for a run without a graph. */
    private List<LinkedRunStep> processSteps(String runId) {
        return linkedSteps.steps(runId).stream().filter(step -> !step.gateway()).toList();
    }

    private record Suggestion(Integer minutes, String from) {
    }

    /**
     * Each crew member's share of their working time from the run's start to its finish (or to now while open), less what
     * counts for them (LB4). Someone without shifts, or a run longer than a year, takes the run's whole time instead, as
     * equipment does (ER4).
     */
    private Map<String, Suggestion> suggestions(ProductionRun run, List<WorkOrderCrew> team, Map<String, Integer> worked) {
        if (team.isEmpty() || run.getCreatedAt() == null) {
            return Map.of();
        }
        OffsetDateTime start = run.getCreatedAt();
        OffsetDateTime end = run.getActualEndAt() != null ? run.getActualEndAt() : OffsetDateTime.now(ZoneOffset.UTC);
        if (!end.isAfter(start)) {
            return Map.of();
        }
        Duration length = Duration.between(start, end);
        Map<String, WorkerTime> times = length.compareTo(WorkOrderCrewService.LONGEST_WINDOW) <= 0
            ? workerCalendars.workingTime(run.getProjectId(), team.stream().map(WorkOrderCrew::getUserId).toList(), start, end)
            : Map.of();
        Map<String, Suggestion> result = new HashMap<>();
        for (WorkOrderCrew member : team) {
            WorkerTime time = times.get(member.getUserId());
            boolean shifts = time != null && time.calendarSet();
            long seconds = shifts ? time.secondsBetween(start.toInstant(), end.toInstant()) : length.getSeconds();
            long minutes = BigDecimal.valueOf(seconds).multiply(BigDecimal.valueOf(WorkOrderCrewService.shareOf(member)))
                .divide(SECONDS_OF_A_FULL_SHARE, 0, RoundingMode.HALF_UP).longValue();
            long left = minutes - worked.getOrDefault(member.getUserId(), 0);
            result.put(member.getUserId(), new Suggestion(left < 1 ? null : (int) Math.min(left, MAX_MINUTES), shifts ? "shifts" : "run"));
        }
        return result;
    }

    /** Whole minutes from {@code from} to {@code to}: later, within a day, not in the future and at least a minute (LB31). */
    private static long between(OffsetDateTime from, OffsetDateTime to) {
        if (!to.isAfter(from)) {
            throw bad("workedTo must be after workedFrom.");
        }
        Duration length = Duration.between(from, to);
        if (length.compareTo(LONGEST_TIMED) > 0) {
            throw bad("Worked time with clock times can be at most 24 hours; record the days apart.");
        }
        if (to.isAfter(OffsetDateTime.now().plus(CLOCK_SKEW))) {
            throw bad("workedTo cannot be in the future.");
        }
        if (length.toMinutes() < 1) {
            throw bad("workedFrom and workedTo must be at least a minute apart.");
        }
        return length.toMinutes();
    }

    private static OffsetDateTime time(String value, String field) {
        String trimmed = ProductionText.trimToNull(value, field);
        if (trimmed == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(trimmed).truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeParseException exception) {
            throw bad(field + " is a date and time with an offset, like 2041-09-01T08:00:00+09:00.");
        }
    }

    private static boolean sameTime(OffsetDateTime one, OffsetDateTime other) {
        return one == null ? other == null : other != null && one.isEqual(other);
    }

    private static String text(String value, String field) {
        String trimmed = ProductionText.trimToNull(value, field);
        if (trimmed != null && trimmed.length() > MAX_TEXT) {
            throw bad(field + " must be at most " + MAX_TEXT + " characters.");
        }
        return trimmed;
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
