package org.myweb.flowmat.domain.production.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogEquipmentView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.catalog.application.publicapi.EquipmentCostQuery;
import org.myweb.flowmat.domain.production.api.dto.request.RunEquipmentTimeCancelRequest;
import org.myweb.flowmat.domain.production.api.dto.request.RunEquipmentTimeRequest;
import org.myweb.flowmat.domain.production.api.dto.response.RunEquipmentTimeResponse;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRunEquipmentTime;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRunSetup;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrder;
import org.myweb.flowmat.domain.production.repository.ProductionRunEquipmentTimeRepository;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.domain.production.repository.ProductionRunSetupRepository;
import org.myweb.flowmat.domain.production.repository.WorkOrderRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Equipment running time of a run with the equipment's rate snapshot (docs/domain/equipment-setup-cost.md ER1-ER7, user
 * decision 2026-10-12). Like setups (AS1-AS6), it is recorded, kept apart from material cost and cancelled rather than
 * deleted; the run's own time suggests how much to record. A finished run's time changes only through a run correction
 * (ER10-ER11).
 */
@Service
@RequiredArgsConstructor
public class RunEquipmentTimeService {

    private static final int SCALE = 4;
    private static final int MAX_MINUTES = 10_080;
    private static final int MAX_TEXT = 500;
    private static final BigDecimal MINUTES_PER_HOUR = BigDecimal.valueOf(60);

    private final ProductionRunRepository runs;
    private final WorkOrderRepository workOrders;
    private final ProductionRunEquipmentTimeRepository times;
    private final ProductionRunSetupRepository setups;
    private final CatalogQuery catalog;
    private final EquipmentCostQuery equipmentCosts;
    private final ProjectAccessService access;
    private final IdGenerator ids;

    @Transactional(readOnly = true)
    public RunEquipmentTimeResponse times(String runId) {
        ProductionRun run = runs.findByProductionRunIdAndDeletedYn(runId, "N")
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        access.requireProjectReadAccess(run.getProjectId());
        return response(run);
    }

    /** Records running time under the run's lock, so it cannot slip past the run finishing (ER2). */
    @Transactional
    public RunEquipmentTimeResponse record(String runId, RunEquipmentTimeRequest request) {
        ProductionRun run = lockedRun(runId);
        access.requireProjectWriteAccess(run.getProjectId());
        if (request == null || request.requestId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "requestId is required.");
        }
        Integer minutes = request.runningMinutes();
        if (minutes == null || minutes < 1 || minutes > MAX_MINUTES) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "runningMinutes must be whole minutes from 1 to " + MAX_MINUTES + ".");
        }
        String note = text(request.note(), "note");
        String requested = ProductionText.trimToNull(request.equipmentId(), "equipmentId");
        String actor = access.requireCurrentUserId();

        ProductionRunEquipmentTime previous = times.findByProductionRunIdAndRequestId(runId, request.requestId()).orElse(null);
        if (previous != null) {
            // The same author sending the same record again after a lost reply gets the run as it is now.
            boolean same = actor.equals(previous.getRecordedBy()) && minutes.equals(previous.getRunningMinutes())
                && Objects.equals(note, previous.getNote())
                && (requested == null || requested.equals(previous.getEquipmentId()));
            if (!same) {
                throw new BusinessException(ErrorCode.CONFLICT, "requestId already belongs to different running time.");
            }
            return response(run);
        }
        ProductionRunServiceImpl.requireOpenRun(run);

        String equipmentId = requested != null ? requested : defaultEquipment(run);
        if (equipmentId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Pick the equipment that ran; this run's work order has none.");
        }
        catalog.findProjectEquipment(run.getProjectId(), equipmentId)
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "Equipment does not exist in this project."));
        EquipmentCostQuery.EquipmentRate rate = equipmentCosts.findHourlyRate(run.getProjectId(), equipmentId)
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "Equipment does not exist in this project."));

        ProductionRunEquipmentTime time = new ProductionRunEquipmentTime();
        time.setRunEquipmentTimeId(ids.generate());
        time.setProductionRunId(runId);
        time.setProjectId(run.getProjectId());
        time.setEquipmentId(equipmentId);
        time.setRunningMinutes(minutes);
        // The rate is copied now: a later rate change does not move what this time cost (ER2, as AS2).
        time.setHourlyCost(rate.hourlyCost());
        time.setHourlyCostVersion(rate.version());
        time.setRunningCost(rate.hourlyCost() == null ? null
            : rate.hourlyCost().multiply(BigDecimal.valueOf(minutes)).divide(MINUTES_PER_HOUR, SCALE, RoundingMode.HALF_UP));
        time.setRequestId(request.requestId());
        time.setNote(note);
        time.setRecordedBy(actor);
        time.setRecordedAt(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
        time.setCancelledYn("N");
        times.saveAndFlush(time);
        return response(run);
    }

    /** Mistaken running time stops counting but stays on record; cancelling it again changes nothing (ER3). */
    @Transactional
    public RunEquipmentTimeResponse cancel(String runId, String runEquipmentTimeId, RunEquipmentTimeCancelRequest request) {
        ProductionRun run = lockedRun(runId);
        access.requireProjectWriteAccess(run.getProjectId());
        ProductionRunEquipmentTime time = times.findById(runEquipmentTimeId)
            .filter(one -> runId.equals(one.getProductionRunId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (time.isCancelled()) {
            return response(run);
        }
        ProductionRunServiceImpl.requireOpenRun(run);
        String reason = text(request == null ? null : request.reason(), "reason");
        if (reason == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Give a reason when cancelling running time.");
        }
        time.setCancelledYn("Y");
        time.setCancelledBy(access.requireCurrentUserId());
        time.setCancelledAt(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
        time.setCancelReason(reason);
        times.saveAndFlush(time);
        return response(run);
    }

    // ---- corrections of finished runs (ER10-ER11): the correction service validates and applies, under the run's lock ----

    /** Running time of the run a correction can cancel: not cancelled already. */
    ProductionRunEquipmentTime findCancellable(ProductionRun run, String runEquipmentTimeId) {
        ProductionText.requireStorable(runEquipmentTimeId, "targetRunEquipmentTimeId");
        if (runEquipmentTimeId == null || runEquipmentTimeId.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Choose the running time to cancel.");
        }
        ProductionRunEquipmentTime time = times.findById(runEquipmentTimeId.trim())
            .filter(one -> run.getProductionRunId().equals(one.getProductionRunId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (time.isCancelled()) {
            throw new BusinessException(ErrorCode.CONFLICT, "This running time was already cancelled by " + time.getCancelledBy() + ".");
        }
        return time;
    }

    /**
     * The equipment of running time a correction would add, checked like a live record (ER1, ER10); blank means the work
     * order's equipment, as when recording.
     */
    String requireCorrectionTime(ProductionRun run, String equipmentId, Integer minutes) {
        if (minutes == null || minutes < 1 || minutes > MAX_MINUTES) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "runningMinutes must be whole minutes from 1 to " + MAX_MINUTES + ".");
        }
        String requested = ProductionText.trimToNull(equipmentId, "equipmentId");
        String id = requested != null ? requested : defaultEquipment(run);
        if (id == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "Choose the equipment of the running time to add; this run's work order has none.");
        }
        catalog.findProjectEquipment(run.getProjectId(), id)
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "Equipment does not exist in this project."));
        return id;
    }

    void cancelForCorrection(ProductionRun run, String runEquipmentTimeId, String reason, String actor) {
        ProductionRunEquipmentTime time = findCancellable(run, runEquipmentTimeId);
        time.setCancelledYn("Y");
        time.setCancelledBy(actor);
        time.setCancelledAt(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
        time.setCancelReason(reason.length() > MAX_TEXT ? reason.substring(0, MAX_TEXT) : reason);
        times.saveAndFlush(time);
    }

    /**
     * Running time added by a correction takes the equipment's rate at the run's original finish, like a corrected setup
     * (AS8); before the first recorded rate change it is estimated (ER11).
     */
    ProductionRunEquipmentTime addForCorrection(ProductionRun run, String correctionLineId, String equipmentId, int minutes,
                                                String note, String actor) {
        EquipmentCostQuery.EquipmentRateAt rate = equipmentCosts.findHourlyRateAt(run.getProjectId(), equipmentId, run.getActualEndAt())
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "Equipment does not exist in this project."));
        ProductionRunEquipmentTime time = new ProductionRunEquipmentTime();
        time.setRunEquipmentTimeId(ids.generate());
        time.setProductionRunId(run.getProductionRunId());
        time.setProjectId(run.getProjectId());
        time.setEquipmentId(equipmentId);
        time.setRunningMinutes(minutes);
        time.setHourlyCost(rate.hourlyCost());
        time.setHourlyCostVersion(rate.version());
        time.setRunningCost(rate.hourlyCost() == null ? null
            : rate.hourlyCost().multiply(BigDecimal.valueOf(minutes)).divide(MINUTES_PER_HOUR, SCALE, RoundingMode.HALF_UP));
        time.setRateBasis(rate.estimated() ? "estimated" : "historical");
        // The correction line is the key, so the same line never adds running time twice.
        time.setRequestId(UUID.nameUUIDFromBytes(("run-correction-line:" + correctionLineId).getBytes(StandardCharsets.UTF_8)));
        time.setNote(note.length() > MAX_TEXT ? note.substring(0, MAX_TEXT) : note);
        time.setRecordedBy(actor);
        time.setRecordedAt(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
        time.setCancelledYn("N");
        return times.saveAndFlush(time);
    }

    private ProductionRun lockedRun(String runId) {
        return runs.findForUpdate(runId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private String defaultEquipment(ProductionRun run) {
        return run.getWorkOrderId() == null ? null
            : workOrders.findByWorkOrderIdAndDeletedYn(run.getWorkOrderId(), "N").map(WorkOrder::getEquipmentId).orElse(null);
    }

    private RunEquipmentTimeResponse response(ProductionRun run) {
        Map<String, CatalogEquipmentView> equipment = new HashMap<>();
        catalog.findProjectEquipments(run.getProjectId()).forEach(one -> equipment.put(one.equipmentId(), one));
        int minutes = 0;
        BigDecimal cost = BigDecimal.ZERO;
        boolean complete = true;
        List<RunEquipmentTimeResponse.Line> lines = new ArrayList<>();
        for (ProductionRunEquipmentTime row : times.findAllByProductionRunIdOrderByRecordedAtAscRunEquipmentTimeIdAsc(run.getProductionRunId())) {
            if (!row.isCancelled()) {
                minutes += row.getRunningMinutes();
                if (row.getRunningCost() == null) {
                    complete = false;
                } else {
                    cost = cost.add(row.getRunningCost());
                }
            }
            CatalogEquipmentView view = equipment.get(row.getEquipmentId());
            String label = view == null ? row.getEquipmentId()
                : view.equipmentCode() != null ? view.equipmentCode() : view.equipmentName();
            lines.add(new RunEquipmentTimeResponse.Line(row.getRunEquipmentTimeId(), row.getEquipmentId(), label, row.getRunningMinutes(),
                row.getHourlyCost(), row.getHourlyCostVersion(), row.getRunningCost(), row.getNote(), row.getRecordedBy(),
                row.getRecordedAt(), row.isCancelled(), row.getCancelledBy(), row.getCancelledAt(), row.getCancelReason(),
                row.getRateBasis()));
        }
        return new RunEquipmentTimeResponse(run.getProductionRunId(), defaultEquipment(run), suggestedMinutes(run, minutes), minutes,
            cost.setScale(SCALE, RoundingMode.HALF_UP), complete, lines);
    }

    /**
     * The run's time from its start to its finish (or to now while open), less the setups and running time that already
     * count, so the same minutes are not charged twice (ER4). Null when nothing is left.
     */
    private Integer suggestedMinutes(ProductionRun run, int recorded) {
        if (run.getCreatedAt() == null) {
            return null;
        }
        OffsetDateTime end = run.getActualEndAt() != null ? run.getActualEndAt() : OffsetDateTime.now(ZoneOffset.UTC);
        long elapsed = Math.max(0, Duration.between(run.getCreatedAt(), end).toMinutes());
        int setupMinutes = setups.findAllByProductionRunIdOrderByRecordedAtAscRunSetupIdAsc(run.getProductionRunId()).stream()
            .filter(setup -> !setup.isCancelled())
            .mapToInt(ProductionRunSetup::getSetupMinutes)
            .sum();
        long left = elapsed - setupMinutes - recorded;
        return left < 1 ? null : (int) Math.min(left, MAX_MINUTES);
    }

    private static String text(String value, String field) {
        String trimmed = ProductionText.trimToNull(value, field);
        if (trimmed != null && trimmed.length() > MAX_TEXT) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, field + " must be at most " + MAX_TEXT + " characters.");
        }
        return trimmed;
    }
}
