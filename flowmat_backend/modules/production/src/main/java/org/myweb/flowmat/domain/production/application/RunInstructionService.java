package org.myweb.flowmat.domain.production.application;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.request.RunInstructionCheckRequest;
import org.myweb.flowmat.domain.production.api.dto.response.RunInstructionResponse;
import org.myweb.flowmat.domain.production.api.dto.response.WorkInstructionResponse;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.myweb.flowmat.domain.production.domain.entity.RunInstructionCheck;
import org.myweb.flowmat.domain.production.domain.entity.WorkInstruction;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.domain.production.repository.RunInstructionCheckRepository;
import org.myweb.flowmat.domain.production.repository.WorkInstructionRepository;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.domain.quality.application.publicapi.InspectionStandardQuery;
import org.myweb.flowmat.domain.quality.application.publicapi.InspectionStandardView;
import org.myweb.flowmat.domain.quality.application.publicapi.RunInspectionCommand;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A production run's instruction checklist (docs/domain/work-instruction.md). A run works to the revision it first
 * confirms a step of; until then it shows the product's released revision, so a new release does not change a checklist
 * already under way. Only open runs (pending or running) change.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RunInstructionService {

    private static final String NOT_DELETED = "N";
    private static final Set<String> OPEN = Set.of("pending", "running");

    private final ProductionRunRepository runRepository;
    private final RunInstructionCheckRepository checkRepository;
    private final WorkInstructionRepository instructionRepository;
    private final WorkInstructionService workInstructionService;
    private final InspectionStandardQuery standardQuery;
    private final RunInspectionCommand inspections;
    private final ProjectAccess projectAccessService;
    private final IdGenerator idGenerator;

    public RunInstructionResponse view(String runId) {
        ProductionRun run = findRun(runId);
        projectAccessService.requireProjectReadAccess(run.getProjectId());
        return response(run);
    }

    @Transactional
    public RunInstructionResponse check(String runId, String stepId, RunInstructionCheckRequest request) {
        ProductionRun run = findOpenRun(runId);
        WorkInstruction instruction = bound(run, checkRepository.findAllByProductionRunId(run.getProductionRunId()));
        if (instruction == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "The run's product has no released work instruction.");
        }
        WorkInstructionResponse.Step step = workInstructionService.response(instruction).steps().stream()
            .filter(candidate -> candidate.stepId().equals(stepId))
            .findFirst()
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (checkRepository.findByProductionRunIdAndStepIdAndUndoneAtIsNull(run.getProductionRunId(), stepId).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Step " + step.stepNo() + " is already confirmed; undo it to confirm it again.");
        }
        String value = ProductionText.trimToNull(request == null ? null : request.value(), "value");
        String note = ProductionText.trimToNull(request == null ? null : request.note(), "note");
        if (step.recordsValue() && value == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "Step " + step.stepNo() + " records " + (step.valueLabel() != null ? step.valueLabel() : "a value") + "; enter it.");
        }
        if (value != null && (step.valueMin() != null || step.valueMax() != null) && number(value) == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Step " + step.stepNo() + " records "
                + (step.valueLabel() != null ? step.valueLabel() : "a value") + " as a number, such as "
                + (step.valueMin() != null ? step.valueMin() : step.valueMax()).stripTrailingZeros().toPlainString() + ".");
        }
        if (value != null && value.length() > 200) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "The value can be at most 200 characters.");
        }
        if (note != null && note.length() > 500) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "The note can be at most 500 characters.");
        }
        InspectionStandardView followed = followedStandard(run, step);
        String result = requestedResult(step, followed, request == null ? null : request.result());
        RunInstructionCheck check = new RunInstructionCheck();
        check.setCheckId(idGenerator.generate());
        check.setProductionRunId(run.getProductionRunId());
        check.setInstructionId(instruction.getInstructionId());
        check.setStepId(stepId);
        check.setCheckValue(value);
        check.setNote(note);
        check.setCheckedBy(projectAccessService.requireCurrentUserId());
        check.setCheckedAt(OffsetDateTime.now());
        RunInspectionCommand.RecordedInspection inspection = inspect(run, step, followed, value, result);
        if (inspection != null) {
            check.setInspectionId(inspection.inspectionId());
            check.setInspectionResult(inspection.result());
        }
        pinRevision(run, instruction);
        checkRepository.saveAndFlush(check);
        return response(run);
    }

    /** The standard a step follows when it can still be followed for this run's product (W13); null otherwise. */
    private InspectionStandardView followedStandard(ProductionRun run, WorkInstructionResponse.Step step) {
        if (step.standardId() == null) {
            return null;
        }
        InspectionStandardView standard = standardQuery.findStandards(List.of(step.standardId())).get(step.standardId());
        return standard != null && standard.active() && !"receipt".equals(standard.stage())
            && standard.itemId().equals(run.getTargetItemId()) ? standard : null;
    }

    /**
     * pass or fail for a step that follows a standard without limits, where a person decides (R10). A step whose standard
     * has limits, or that follows none, takes no result: its value decides, or there is no inspection.
     */
    private static String requestedResult(WorkInstructionResponse.Step step, InspectionStandardView standard, String sent) {
        String result = sent == null || sent.isBlank() ? null : sent.trim().toLowerCase(Locale.ROOT);
        boolean personDecides = standard != null && standard.standardMin() == null && standard.standardMax() == null;
        if (!personDecides) {
            if (result != null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Step " + step.stepNo() + " takes no result; "
                    + (standard == null ? "it does not follow a pass/fail standard." : "its value decides the " + standard.inspectionType() + " result."));
            }
            return null;
        }
        if (!"pass".equals(result) && !"fail".equals(result)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "Step " + step.stepNo() + " follows the " + standard.inspectionType() + " check; say whether it passed (pass or fail).");
        }
        return result;
    }

    /**
     * A step that follows an inspection standard records the run's inspection too (R9-R10), so the same check is not
     * entered twice: with limits its number decides the result, without them the person's pass or fail does and the value
     * is what they saw. A standard that can no longer be followed, or a value that is not a number, leaves only the
     * confirmation.
     */
    private RunInspectionCommand.RecordedInspection inspect(ProductionRun run, WorkInstructionResponse.Step step,
                                                            InspectionStandardView standard, String value, String result) {
        if (standard == null) {
            return null;
        }
        String note = "Work instruction step " + step.stepNo() + ": " + step.text();
        if (result != null) {
            return inspections.recordForRun(run.getProjectId(), run.getProductionRunId(), standard.standardId(),
                standard.inspectionType(), null, result, value == null ? note : note + " (" + value + ")");
        }
        java.math.BigDecimal measured = number(value);
        return measured == null ? null : inspections.recordForRun(run.getProjectId(), run.getProductionRunId(),
            standard.standardId(), standard.inspectionType(), measured, null, note);
    }

    @Transactional
    public RunInstructionResponse uncheck(String runId, String stepId) {
        ProductionRun run = findOpenRun(runId);
        RunInstructionCheck check = checkRepository.findByProductionRunIdAndStepIdAndUndoneAtIsNull(run.getProductionRunId(), stepId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        // Older application instances may have written checks without the persisted binding.
        if (run.getWorkInstructionId() == null) {
            pinRevision(run, bound(run, checkRepository.findAllByProductionRunId(run.getProductionRunId())));
        }
        // Kept as history: who confirmed what, and who undid it when (R6).
        check.setUndoneBy(projectAccessService.requireCurrentUserId());
        check.setUndoneAt(OffsetDateTime.now());
        checkRepository.saveAndFlush(check);
        return response(run);
    }

    /**
     * Finishing a run whose instruction revision blocks finishing needs every required step confirmed. The caller has
     * checked access and that the run is open.
     */
    public void requireCompleteToFinish(ProductionRun run) {
        RunInstructionResponse checklist = response(run);
        if (checklist.instruction() != null && checklist.instruction().blocksFinish() && !checklist.complete()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Confirm the required work instruction steps first ("
                + checklist.requiredDone() + " of " + checklist.requiredSteps() + " done).");
        }
    }

    /**
     * A process's required work instruction steps and how many are confirmed (docs/architecture/adr/ADR-011-production-steps.md
     * decision 7); {@code missing} are their step numbers, {@code blocksFinish} whether the instruction blocks finishing.
     */
    public record NodeChecklist(int required, int done, List<Integer> missing, boolean blocksFinish) {
    }

    /** The run's checklist by process: one entry for each process a required step names; none without an instruction. */
    public Map<String, NodeChecklist> byNode(ProductionRun run) {
        RunInstructionResponse checklist = response(run);
        if (checklist.instruction() == null) {
            return Map.of();
        }
        Set<String> confirmed = checklist.checks().stream().map(RunInstructionResponse.Check::stepId).collect(Collectors.toSet());
        Map<String, List<WorkInstructionResponse.Step>> byProcess = checklist.instruction().steps().stream()
            .filter(step -> step.required() && step.processId() != null)
            .collect(Collectors.groupingBy(WorkInstructionResponse.Step::processId));
        Map<String, NodeChecklist> nodes = new HashMap<>();
        byProcess.forEach((processId, steps) -> {
            List<Integer> missing = steps.stream().filter(step -> !confirmed.contains(step.stepId()))
                .map(WorkInstructionResponse.Step::stepNo).sorted().toList();
            nodes.put(processId, new NodeChecklist(steps.size(), steps.size() - missing.size(), missing,
                checklist.instruction().blocksFinish()));
        });
        return nodes;
    }

    private RunInstructionResponse response(ProductionRun run) {
        List<RunInstructionCheck> checks = checkRepository.findAllByProductionRunIdAndUndoneAtIsNull(run.getProductionRunId());
        WorkInstruction instruction = bound(run, checks);
        boolean open = isOpen(run);
        if (instruction == null) {
            return new RunInstructionResponse(run.getProductionRunId(), open, null, List.of(), 0, 0, true, List.of());
        }
        WorkInstructionResponse body = workInstructionService.response(instruction);
        Map<String, RunInstructionCheck> byStep = checks.stream()
            .filter(check -> check.getInstructionId().equals(instruction.getInstructionId()))
            .collect(Collectors.toMap(RunInstructionCheck::getStepId, Function.identity(), (one, other) -> one));
        List<RunInstructionResponse.Check> checkResponses = body.steps().stream()
            .filter(step -> byStep.containsKey(step.stepId()))
            .map(step -> {
                RunInstructionCheck check = byStep.get(step.stepId());
                return new RunInstructionResponse.Check(step.stepId(), check.getCheckValue(), check.getNote(), check.getCheckedBy(),
                    check.getCheckedAt(), outsideLimits(step, check.getCheckValue()), check.getInspectionId(),
                    check.getInspectionResult());
            })
            .toList();
        int required = (int) body.steps().stream().filter(WorkInstructionResponse.Step::required).count();
        int done = (int) body.steps().stream().filter(step -> step.required() && byStep.containsKey(step.stepId())).count();
        Map<String, Integer> stepNos = body.steps().stream()
            .collect(Collectors.toMap(WorkInstructionResponse.Step::stepId, WorkInstructionResponse.Step::stepNo));
        List<RunInstructionResponse.Undone> undone = checkRepository
            .findAllByProductionRunIdAndUndoneAtIsNotNullOrderByUndoneAtAsc(run.getProductionRunId()).stream()
            .filter(check -> check.getInstructionId().equals(instruction.getInstructionId()) && stepNos.containsKey(check.getStepId()))
            .map(check -> new RunInstructionResponse.Undone(check.getStepId(), stepNos.get(check.getStepId()), check.getCheckValue(),
                check.getNote(), check.getCheckedBy(), check.getCheckedAt(), check.getUndoneBy(), check.getUndoneAt()))
            .toList();
        return new RunInstructionResponse(run.getProductionRunId(), open, body, checkResponses, required, done, done == required, undone);
    }

    /** A recorded value below the step's lower limit or above its upper one (R7). */
    private static boolean outsideLimits(WorkInstructionResponse.Step step, String value) {
        java.math.BigDecimal number = number(value);
        if (number == null) {
            return false;
        }
        return (step.valueMin() != null && number.compareTo(step.valueMin()) < 0)
            || (step.valueMax() != null && number.compareTo(step.valueMax()) > 0);
    }

    private static java.math.BigDecimal number(String value) {
        try {
            return value == null ? null : new java.math.BigDecimal(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** The revision of the run's first confirmation, or the product's released revision before any. */
    private WorkInstruction bound(ProductionRun run, List<RunInstructionCheck> checks) {
        if (run.getWorkInstructionId() != null) {
            return instructionRepository.findById(run.getWorkInstructionId()).orElse(null);
        }
        if (!checks.isEmpty()) {
            RunInstructionCheck first = checks.stream()
                .min(Comparator.comparing(RunInstructionCheck::getCheckedAt).thenComparing(RunInstructionCheck::getCheckId))
                .orElseThrow();
            return instructionRepository.findById(first.getInstructionId()).orElse(null);
        }
        return workInstructionService.released(run.getTargetItemId());
    }

    private void pinRevision(ProductionRun run, WorkInstruction instruction) {
        if (run.getWorkInstructionId() == null && instruction != null) {
            run.setWorkInstructionId(instruction.getInstructionId());
            runRepository.save(run);
        }
    }

    private static boolean isOpen(ProductionRun run) {
        return OPEN.contains(run.getRunStatus() == null ? "" : run.getRunStatus().trim().toLowerCase(Locale.ROOT));
    }

    private ProductionRun findOpenRun(String runId) {
        ProductionRun run = runRepository.findForUpdate(runId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        projectAccessService.requireProjectWriteAccess(run.getProjectId());
        if (!isOpen(run)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Run " + run.getRunNumber() + " is " + run.getRunStatus()
                + "; its checklist can no longer change.");
        }
        return run;
    }

    private ProductionRun findRun(String runId) {
        return runRepository.findByProductionRunIdAndDeletedYn(runId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
}
