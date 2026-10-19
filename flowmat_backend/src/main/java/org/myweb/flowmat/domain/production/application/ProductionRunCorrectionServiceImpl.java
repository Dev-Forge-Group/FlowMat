package org.myweb.flowmat.domain.production.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.application.publicapi.LotQuery;
import org.myweb.flowmat.domain.inventory.application.publicapi.LotView;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockQuery;
import org.myweb.flowmat.domain.production.api.dto.request.RunCorrectionCreateRequest;
import org.myweb.flowmat.domain.production.api.dto.request.RunCorrectionLineRequest;
import org.myweb.flowmat.domain.production.api.dto.response.RunCorrectionResponse;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRunCorrection;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRunCorrectionLine;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRunItem;
import org.myweb.flowmat.domain.production.repository.ProductionRunCorrectionLineRepository;
import org.myweb.flowmat.domain.production.repository.ProductionRunCorrectionRepository;
import org.myweb.flowmat.domain.production.repository.ProductionRunItemRepository;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.domain.notification.application.publicapi.NotificationCommand;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectMemberQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Corrections of finished runs (docs/domain/production-run-correction.md): requested with a reason, approved by the
 * project owner, and applied in the approving transaction. Recordings are voided and added, never edited. The owner hears of
 * each request and the requester of the decision (docs/domain/notification.md NT8).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductionRunCorrectionServiceImpl implements ProductionRunCorrectionService {

    private static final String NOT_DELETED = "N";
    private static final String FINISHED = "finished";

    private final ProductionRunRepository productionRunRepository;
    private final ProductionRunItemRepository productionRunItemRepository;
    private final ProductionRunCorrectionRepository correctionRepository;
    private final ProductionRunCorrectionLineRepository correctionLineRepository;
    private final ProductionRunServiceImpl productionRunService;
    private final ProjectAccessService projectAccessService;
    private final StockQuery stockQuery;
    private final LotQuery lotQuery;
    private final IdGenerator idGenerator;
    private final RunSetupService runSetupService;
    private final RunEquipmentTimeService runEquipmentTimeService;
    private final RunLaborService runLaborService;
    private final ProjectMemberQuery projectMembers;
    private final NotificationCommand notifications;

    @Override
    public List<RunCorrectionResponse> listCorrections(String productionRunId) {
        ProductionRun run = productionRunRepository.findByProductionRunIdAndDeletedYn(productionRunId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        projectAccessService.requireProjectReadAccess(run.getProjectId());
        List<ProductionRunCorrection> corrections = correctionRepository.findAllByProductionRunIdOrderByCorrectionNoDesc(productionRunId);
        if (corrections.isEmpty()) {
            return List.of();
        }
        Map<String, List<ProductionRunCorrectionLine>> linesByCorrection = correctionLineRepository
            .findAllByProductionRunCorrectionIdInOrderByLineNoAsc(
                corrections.stream().map(ProductionRunCorrection::getProductionRunCorrectionId).toList())
            .stream()
            .collect(Collectors.groupingBy(ProductionRunCorrectionLine::getProductionRunCorrectionId, LinkedHashMap::new,
                Collectors.toList()));
        return corrections.stream()
            .map(correction -> toResponse(correction,
                linesByCorrection.getOrDefault(correction.getProductionRunCorrectionId(), List.of())))
            .toList();
    }

    @Override
    @Transactional
    public RunCorrectionResponse requestCorrection(String productionRunId, RunCorrectionCreateRequest request) {
        ProductionRun run = lockRun(productionRunId);
        projectAccessService.requireProjectWriteAccess(run.getProjectId());
        requireFinished(run);
        if (correctionRepository.existsByProductionRunIdAndStatus(productionRunId, ProductionRunCorrection.PENDING_APPROVAL)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "A correction of run " + run.getRunNumber() + " is already waiting for approval. Approve or reject it first.");
        }
        String normalizedReason = ProductionText.trimToNull(request.reason(), "reason");
        if (normalizedReason == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Give a reason for the correction.");
        }
        List<ProductionRunCorrectionLine> lines = validateLines(run, request.lines());

        ProductionRunCorrection correction = new ProductionRunCorrection();
        correction.setProductionRunCorrectionId(idGenerator.generate());
        correction.setProjectId(run.getProjectId());
        correction.setProductionRunId(run.getProductionRunId());
        correction.setCorrectionNo(correctionRepository.findTopByProductionRunIdOrderByCorrectionNoDesc(productionRunId)
            .map(latest -> latest.getCorrectionNo() + 1)
            .orElse(1));
        correction.setStatus(ProductionRunCorrection.PENDING_APPROVAL);
        correction.setReason(normalizedReason);
        correction.setRequestedBy(projectAccessService.requireCurrentUserId());
        correction.setRequestedAt(OffsetDateTime.now());
        ProductionRunCorrection saved = correctionRepository.save(correction);
        for (ProductionRunCorrectionLine line : lines) {
            line.setProductionRunCorrectionId(saved.getProductionRunCorrectionId());
        }
        List<ProductionRunCorrectionLine> savedLines = correctionLineRepository.saveAll(lines);
        // The project owner approves, so they are told; an owner asking for a correction themselves is not (NT8).
        notifications.notifyAssignee(projectMembers.ownerOf(run.getProjectId()), saved.getRequestedBy(), run.getProjectId(),
            "correction_approval", label(saved, run) + " waits for your approval", normalizedReason, "production_run",
            run.getProductionRunId());
        return toResponse(saved, savedLines);
    }

    @Override
    @Transactional
    public RunCorrectionResponse approveCorrection(String productionRunId, String correctionId) {
        ProductionRun run = lockRun(productionRunId);
        projectAccessService.requireProjectOwnerAccess(run.getProjectId());
        ProductionRunCorrection correction = findPending(run, correctionId);
        requireFinished(run);
        String actor = projectAccessService.requireCurrentUserId();
        int no = correction.getCorrectionNo();
        String reason = correction.getReason();
        List<ProductionRunCorrectionLine> lines =
            correctionLineRepository.findAllByProductionRunCorrectionIdOrderByLineNoAsc(correctionId);

        // Void first: produced stock that was already used is refused before anything moves.
        List<ProductionRunItem> toVoid = new ArrayList<>();
        Set<String> formerOutputLots = new HashSet<>();
        for (ProductionRunCorrectionLine line : lines) {
            if (ProductionRunCorrectionLine.VOID_ITEM.equals(line.getLineKind())) {
                ProductionRunItem item = findVoidable(run, line.getTargetRunItemId());
                if ("output".equals(item.getDirection())) {
                    requireOutputUnused(run, item);
                    if (item.getLotId() != null) {
                        formerOutputLots.add(item.getLotId());
                    }
                }
                toVoid.add(item);
            }
        }
        for (ProductionRunItem item : toVoid) {
            productionRunService.voidForCorrection(run, item, correctionId, no, reason, actor);
        }
        for (ProductionRunCorrectionLine line : lines) {
            if (ProductionRunCorrectionLine.ADD_ITEM.equals(line.getLineKind())) {
                ProductionRunItem created = productionRunService.recordCorrectionItem(run, correctionId, line.getDirection(),
                    line.getItemId(), line.getInventoryId(), line.getQty(), line.getUnit());
                line.setCreatedRunItemId(created.getProductionRunItemId());
            } else if (ProductionRunCorrectionLine.SET_OUTPUT_QTY.equals(line.getLineKind())) {
                BigDecimal current = run.getActualOutputQty();
                if (!sameQuantity(current, line.getBeforeQty())) {
                    throw new BusinessException(ErrorCode.CONFLICT, "The run's output changed from " + plain(line.getBeforeQty())
                        + " to " + plain(current) + " since this correction was requested. Reject it and request a new one.");
                }
                run.setActualOutputQty(line.getAfterQty());
                productionRunRepository.save(run);
            }
        }
        productionRunService.rebuildLotGenealogy(run, formerOutputLots);
        // Setups, running time and worked time last: they move no stock (docs/domain/equipment-setup-cost.md AS7-AS8,
        // ER10-ER11; docs/domain/worker-calendar.md LB10).
        String costNote = "Correction #" + no + ": " + reason;
        for (ProductionRunCorrectionLine line : lines) {
            if (ProductionRunCorrectionLine.CANCEL_SETUP.equals(line.getLineKind())) {
                runSetupService.cancelForCorrection(run, line.getTargetRunSetupId(), costNote, actor);
            } else if (ProductionRunCorrectionLine.ADD_SETUP.equals(line.getLineKind())) {
                line.setCreatedRunSetupId(runSetupService.addForCorrection(run, line.getProductionRunCorrectionLineId(),
                    line.getEquipmentId(), line.getSetupMinutes(), costNote, actor).getRunSetupId());
            } else if (ProductionRunCorrectionLine.CANCEL_RUNNING_TIME.equals(line.getLineKind())) {
                runEquipmentTimeService.cancelForCorrection(run, line.getTargetRunEquipmentTimeId(), costNote, actor);
            } else if (ProductionRunCorrectionLine.ADD_RUNNING_TIME.equals(line.getLineKind())) {
                line.setCreatedRunEquipmentTimeId(runEquipmentTimeService.addForCorrection(run,
                    line.getProductionRunCorrectionLineId(), line.getEquipmentId(), line.getRunningMinutes(), costNote, actor)
                    .getRunEquipmentTimeId());
            } else if (ProductionRunCorrectionLine.CANCEL_LABOR.equals(line.getLineKind())) {
                runLaborService.cancelForCorrection(run, line.getTargetRunLaborId(), costNote, actor);
            } else if (ProductionRunCorrectionLine.ADD_LABOR.equals(line.getLineKind())) {
                line.setCreatedRunLaborId(runLaborService.addForCorrection(run, line.getProductionRunCorrectionLineId(),
                    line.getLaborUserId(), line.getWorkedMinutes(), line.getLaborStepId(), costNote, actor).getRunLaborId());
            }
        }

        OffsetDateTime now = OffsetDateTime.now();
        correction.setStatus(ProductionRunCorrection.APPLIED);
        correction.setDecidedBy(actor);
        correction.setDecidedAt(now);
        correction.setAppliedAt(now);
        ProductionRunCorrection decided = correctionRepository.save(correction);
        tellRequester(decided, run, actor, "approved", "Its changes are applied.");
        return toResponse(decided, correctionLineRepository.saveAll(lines));
    }

    @Override
    @Transactional
    public RunCorrectionResponse rejectCorrection(String productionRunId, String correctionId, String note) {
        ProductionRun run = lockRun(productionRunId);
        projectAccessService.requireProjectOwnerAccess(run.getProjectId());
        ProductionRunCorrection correction = findPending(run, correctionId);
        String normalizedNote = ProductionText.trimToNull(note, "note");
        if (normalizedNote == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "note is required for rejecting the correction.");
        }
        correction.setStatus(ProductionRunCorrection.REJECTED);
        correction.setDecidedBy(projectAccessService.requireCurrentUserId());
        correction.setDecidedAt(OffsetDateTime.now());
        correction.setDecisionNote(normalizedNote);
        ProductionRunCorrection decided = correctionRepository.save(correction);
        tellRequester(decided, run, decided.getDecidedBy(), "rejected", normalizedNote);
        return toResponse(decided, correctionLineRepository.findAllByProductionRunCorrectionIdOrderByLineNoAsc(correctionId));
    }

    /** The person who asked for the correction hears the owner's decision (NT8); not when they decided it themselves. */
    private void tellRequester(ProductionRunCorrection correction, ProductionRun run, String actor, String decision, String body) {
        notifications.notifyAssignee(correction.getRequestedBy(), actor, run.getProjectId(), "correction_decision",
            label(correction, run) + " was " + decision, body, "production_run", run.getProductionRunId());
    }

    private static String label(ProductionRunCorrection correction, ProductionRun run) {
        return "Correction #" + correction.getCorrectionNo() + " of " + run.getRunNumber();
    }

    private List<ProductionRunCorrectionLine> validateLines(ProductionRun run, List<RunCorrectionLineRequest> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "A correction needs at least one change.");
        }
        List<ProductionRunCorrectionLine> lines = new ArrayList<>();
        Set<String> voided = new HashSet<>();
        Set<String> cancelledSetups = new HashSet<>();
        Set<String> cancelledTimes = new HashSet<>();
        Set<String> cancelledLabor = new HashSet<>();
        boolean outputSet = false;
        int lineNo = 1;
        for (RunCorrectionLineRequest request : requested) {
            if (request == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "lines must not contain null entries.");
            }
            ProductionText.requireStorable(request.kind(), "kind");
            String kind = request.kind() == null ? "" : request.kind().trim().toLowerCase(Locale.ROOT);
            ProductionRunCorrectionLine line = new ProductionRunCorrectionLine();
            line.setProductionRunCorrectionLineId(idGenerator.generate());
            line.setLineNo(lineNo++);
            line.setLineKind(kind);
            switch (kind) {
                case ProductionRunCorrectionLine.VOID_ITEM -> {
                    ProductionRunItem item = findVoidable(run, request.targetRunItemId());
                    if (!voided.add(item.getProductionRunItemId())) {
                        throw new BusinessException(ErrorCode.BAD_REQUEST, "The same recording is voided twice.");
                    }
                    line.setTargetRunItemId(item.getProductionRunItemId());
                }
                case ProductionRunCorrectionLine.ADD_ITEM -> {
                    productionRunService.validateCorrectionRecording(run, request.direction(), request.itemId(),
                        request.inventoryId(), request.qty(), request.unit());
                    line.setDirection(request.direction().trim().toLowerCase(Locale.ROOT));
                    line.setItemId(request.itemId().trim());
                    line.setInventoryId(request.inventoryId() == null || request.inventoryId().isBlank()
                        ? null : request.inventoryId().trim());
                    line.setQty(ProductionRunServiceImpl.storedQuantity(request.qty(), "qty"));
                    line.setUnit(ProductionRunServiceImpl.storedUnit(request.unit()));
                }
                case ProductionRunCorrectionLine.SET_OUTPUT_QTY -> {
                    if (outputSet) {
                        throw new BusinessException(ErrorCode.BAD_REQUEST, "Set the output quantity once per correction.");
                    }
                    outputSet = true;
                    if (request.afterQty() == null || request.afterQty().signum() < 0) {
                        throw new BusinessException(ErrorCode.BAD_REQUEST, "The corrected output quantity must be 0 or more.");
                    }
                    BigDecimal afterQty = ProductionRunServiceImpl.storedQuantity(request.afterQty(), "afterQty");
                    if (sameQuantity(run.getActualOutputQty(), afterQty)) {
                        throw new BusinessException(ErrorCode.BAD_REQUEST,
                            "The run's output is already " + plain(afterQty) + ".");
                    }
                    line.setBeforeQty(run.getActualOutputQty());
                    line.setAfterQty(afterQty);
                }
                case ProductionRunCorrectionLine.CANCEL_SETUP -> {
                    String setupId = runSetupService.findCancellable(run, request.targetRunSetupId()).getRunSetupId();
                    if (!cancelledSetups.add(setupId)) {
                        throw new BusinessException(ErrorCode.BAD_REQUEST, "The same setup is cancelled twice.");
                    }
                    line.setTargetRunSetupId(setupId);
                }
                case ProductionRunCorrectionLine.ADD_SETUP -> {
                    line.setEquipmentId(runSetupService.requireCorrectionSetup(run, request.equipmentId(), request.setupMinutes()));
                    line.setSetupMinutes(request.setupMinutes());
                }
                case ProductionRunCorrectionLine.CANCEL_RUNNING_TIME -> {
                    String timeId = runEquipmentTimeService.findCancellable(run, request.targetRunEquipmentTimeId())
                        .getRunEquipmentTimeId();
                    if (!cancelledTimes.add(timeId)) {
                        throw new BusinessException(ErrorCode.BAD_REQUEST, "The same running time is cancelled twice.");
                    }
                    line.setTargetRunEquipmentTimeId(timeId);
                }
                case ProductionRunCorrectionLine.ADD_RUNNING_TIME -> {
                    line.setEquipmentId(runEquipmentTimeService.requireCorrectionTime(run, request.equipmentId(),
                        request.runningMinutes()));
                    line.setRunningMinutes(request.runningMinutes());
                }
                case ProductionRunCorrectionLine.CANCEL_LABOR -> {
                    String laborId = runLaborService.findCancellable(run, request.targetRunLaborId()).getRunLaborId();
                    if (!cancelledLabor.add(laborId)) {
                        throw new BusinessException(ErrorCode.BAD_REQUEST, "The same worked time is cancelled twice.");
                    }
                    line.setTargetRunLaborId(laborId);
                }
                case ProductionRunCorrectionLine.ADD_LABOR -> {
                    line.setLaborUserId(runLaborService.requireCorrectionLabor(run, request.userId(), request.workedMinutes()));
                    line.setWorkedMinutes(request.workedMinutes());
                    line.setLaborStepId(runLaborService.requireCorrectionStep(run, request.stepId()));
                }
                default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "Unknown correction kind '" + request.kind()
                    + "'. Use void_item, add_item, set_output_qty, cancel_setup, add_setup, cancel_running_time,"
                    + " add_running_time, cancel_labor or add_labor.");
            }
            lines.add(line);
        }
        return lines;
    }

    /** A recording of this run that stands: not a BOM plan line and not cancelled already. */
    private ProductionRunItem findVoidable(ProductionRun run, String runItemId) {
        ProductionText.requireStorable(runItemId, "targetRunItemId");
        if (runItemId == null || runItemId.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Choose the recording to void.");
        }
        ProductionRunItem item = productionRunItemRepository.findById(runItemId.trim())
            .filter(found -> found.getProductionRunId().equals(run.getProductionRunId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if ("bom".equals(item.getQuantitySource())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "BOM plan lines are the plan, not a recording; there is nothing to void.");
        }
        if (item.isCancelled()) {
            throw new BusinessException(ErrorCode.CONFLICT, "This recording was already cancelled by " + item.getCancelledBy() + ".");
        }
        return item;
    }

    /**
     * Voiding an output takes its stock back. When that stock was already used, say where it went: later runs have to be
     * corrected first (§9 Q3).
     */
    private void requireOutputUnused(ProductionRun run, ProductionRunItem output) {
        if (output.getInventoryId() == null) {
            return;
        }
        BigDecimal produced = stockQuery.quantityMovedBy("production_run_item", output.getProductionRunItemId());
        BigDecimal available = stockQuery.availableQuantity(output.getInventoryId()).orElse(null);
        if (produced.signum() <= 0 || available == null) {
            return;
        }
        if (available.compareTo(produced) >= 0) {
            return;
        }
        LotView lot = output.getLotId() == null ? null : lotQuery.findLots(List.of(output.getLotId())).get(output.getLotId());
        String label = output.getLotId() == null
            ? "The stock this run produced"
            : "LOT " + (lot == null ? output.getLotId() : lot.lotNo()) + " made by this run";
        List<String> laterRuns = output.getLotId() == null ? List.of() : consumingRunNumbers(run, output.getLotId());
        throw new BusinessException(ErrorCode.CONFLICT, label + " was already used"
            + (laterRuns.isEmpty()
                ? " (issued, reserved or moved); bring it back before voiding the output."
                : " by run " + String.join(", ", laterRuns) + "; correct that run first."));
    }

    /** Runs with an unreversed stock consumption of this LOT (genealogy edges also need an output). */
    private List<String> consumingRunNumbers(ProductionRun run, String lotId) {
        List<String> runIds = productionRunItemRepository.findConsumingRunIdsByLotId(lotId).stream()
            .filter(id -> !id.equals(run.getProductionRunId()))
            .toList();
        return productionRunRepository.findAllById(runIds).stream()
            .map(ProductionRun::getRunNumber)
            .sorted()
            .toList();
    }

    private ProductionRun lockRun(String productionRunId) {
        return productionRunRepository.findForUpdate(productionRunId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private ProductionRunCorrection findPending(ProductionRun run, String correctionId) {
        ProductionRunCorrection correction = correctionRepository.findById(correctionId)
            .filter(found -> found.getProductionRunId().equals(run.getProductionRunId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!correction.isPending()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "Correction #" + correction.getCorrectionNo() + " is already " + correction.getStatus() + ".");
        }
        return correction;
    }

    private static void requireFinished(ProductionRun run) {
        if (!FINISHED.equalsIgnoreCase(run.getRunStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Only finished runs are corrected. Run " + run.getRunNumber()
                + " is " + run.getRunStatus() + "; cancel its recordings on the run instead.");
        }
    }

    private static boolean sameQuantity(BigDecimal a, BigDecimal b) {
        return zeroIfNull(a).compareTo(zeroIfNull(b)) == 0;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String plain(BigDecimal value) {
        return zeroIfNull(value).stripTrailingZeros().toPlainString();
    }

    private static RunCorrectionResponse toResponse(ProductionRunCorrection correction, List<ProductionRunCorrectionLine> lines) {
        return new RunCorrectionResponse(
            correction.getProductionRunCorrectionId(),
            correction.getProductionRunId(),
            correction.getCorrectionNo(),
            correction.getStatus(),
            correction.getReason(),
            correction.getRequestedBy(),
            correction.getRequestedAt(),
            correction.getDecidedBy(),
            correction.getDecidedAt(),
            correction.getDecisionNote(),
            correction.getAppliedAt(),
            lines.stream()
                .map(line -> new RunCorrectionResponse.Line(
                    line.getLineNo(),
                    line.getLineKind(),
                    line.getTargetRunItemId(),
                    line.getDirection(),
                    line.getItemId(),
                    line.getInventoryId(),
                    line.getQty(),
                    line.getUnit(),
                    line.getBeforeQty(),
                    line.getAfterQty(),
                    line.getCreatedRunItemId(),
                    line.getTargetRunSetupId(),
                    line.getEquipmentId(),
                    line.getSetupMinutes(),
                    line.getCreatedRunSetupId(),
                    line.getTargetRunEquipmentTimeId(),
                    line.getRunningMinutes(),
                    line.getCreatedRunEquipmentTimeId(),
                    line.getTargetRunLaborId(),
                    line.getLaborUserId(),
                    line.getWorkedMinutes(),
                    line.getCreatedRunLaborId(),
                    line.getLaborStepId()))
                .toList()
        );
    }
}
