package org.myweb.flowmat.domain.production.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogEquipmentView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.catalog.application.publicapi.EquipmentCostQuery;
import org.myweb.flowmat.domain.production.api.dto.request.RunSetupCancelRequest;
import org.myweb.flowmat.domain.production.api.dto.request.RunSetupRequest;
import org.myweb.flowmat.domain.production.api.dto.response.RunSetupCostResponse;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRunSetup;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrder;
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
 * Actual setups of a run with the equipment's rate snapshot (DECISIONS-2026-10-05 section 4,
 * docs/domain/equipment-setup-cost.md AS1-AS6). The planned estimate stays on the equipment load; this is what happened.
 */
@Service
@RequiredArgsConstructor
public class RunSetupService {

    private static final int SCALE = 4;
    private static final int MAX_MINUTES = 1440;
    private static final int MAX_TEXT = 500;
    private static final BigDecimal MINUTES_PER_HOUR = BigDecimal.valueOf(60);

    private final ProductionRunRepository runs;
    private final WorkOrderRepository workOrders;
    private final ProductionRunSetupRepository setups;
    private final CatalogQuery catalog;
    private final EquipmentCostQuery equipmentCosts;
    private final ProjectAccessService access;
    private final IdGenerator ids;

    @Transactional(readOnly = true)
    public RunSetupCostResponse setups(String runId) {
        ProductionRun run = runs.findByProductionRunIdAndDeletedYn(runId, "N")
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        access.requireProjectReadAccess(run.getProjectId());
        return response(run);
    }

    /** Records a setup under the run's lock, so it cannot slip past the run finishing (AS1-AS3). */
    @Transactional
    public RunSetupCostResponse record(String runId, RunSetupRequest request) {
        ProductionRun run = lockedRun(runId);
        access.requireProjectWriteAccess(run.getProjectId());
        if (request == null || request.requestId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "requestId is required.");
        }
        Integer minutes = request.setupMinutes();
        if (minutes == null || minutes < 1 || minutes > MAX_MINUTES) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "setupMinutes must be whole minutes from 1 to " + MAX_MINUTES + ".");
        }
        String note = text(request.note(), "note");
        String requested = ProductionText.trimToNull(request.equipmentId(), "equipmentId");
        String actor = access.requireCurrentUserId();

        ProductionRunSetup previous = setups.findByProductionRunIdAndRequestId(runId, request.requestId()).orElse(null);
        if (previous != null) {
            // The same author sending the same setup again after a lost reply gets the run as it is now.
            boolean same = actor.equals(previous.getRecordedBy()) && minutes.equals(previous.getSetupMinutes())
                && Objects.equals(note, previous.getNote())
                && (requested == null || requested.equals(previous.getEquipmentId()));
            if (!same) {
                throw new BusinessException(ErrorCode.CONFLICT, "requestId already belongs to a different setup.");
            }
            return response(run);
        }
        ProductionRunServiceImpl.requireOpenRun(run);

        String equipmentId = requested != null ? requested : defaultEquipment(run);
        if (equipmentId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Pick the equipment the setup was on; this run's work order has none.");
        }
        catalog.findProjectEquipment(run.getProjectId(), equipmentId)
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "Equipment does not exist in this project."));
        EquipmentCostQuery.EquipmentRate rate = equipmentCosts.findHourlyRate(run.getProjectId(), equipmentId)
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "Equipment does not exist in this project."));

        ProductionRunSetup setup = new ProductionRunSetup();
        setup.setRunSetupId(ids.generate());
        setup.setProductionRunId(runId);
        setup.setProjectId(run.getProjectId());
        setup.setEquipmentId(equipmentId);
        setup.setSetupMinutes(minutes);
        // The rate is copied now: a later rate change does not move what this setup cost (AS2).
        setup.setHourlyCost(rate.hourlyCost());
        setup.setHourlyCostVersion(rate.version());
        setup.setSetupCost(rate.hourlyCost() == null ? null
            : rate.hourlyCost().multiply(BigDecimal.valueOf(minutes)).divide(MINUTES_PER_HOUR, SCALE, RoundingMode.HALF_UP));
        setup.setRequestId(request.requestId());
        setup.setNote(note);
        setup.setRecordedBy(actor);
        setup.setRecordedAt(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
        setup.setCancelledYn("N");
        setups.saveAndFlush(setup);
        return response(run);
    }

    /** A mistaken setup stops counting but stays on record; cancelling it again changes nothing (AS4). */
    @Transactional
    public RunSetupCostResponse cancel(String runId, String runSetupId, RunSetupCancelRequest request) {
        ProductionRun run = lockedRun(runId);
        access.requireProjectWriteAccess(run.getProjectId());
        ProductionRunSetup setup = setups.findById(runSetupId)
            .filter(one -> runId.equals(one.getProductionRunId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (setup.isCancelled()) {
            return response(run);
        }
        ProductionRunServiceImpl.requireOpenRun(run);
        String reason = text(request == null ? null : request.reason(), "reason");
        if (reason == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Give a reason when cancelling a setup.");
        }
        setup.setCancelledYn("Y");
        setup.setCancelledBy(access.requireCurrentUserId());
        setup.setCancelledAt(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
        setup.setCancelReason(reason);
        setups.saveAndFlush(setup);
        return response(run);
    }

    // ---- corrections of finished runs (AS7-AS8): the correction service validates and applies, under the run's lock ----

    /** A setup of the run a correction can cancel: not cancelled already. */
    ProductionRunSetup findCancellable(ProductionRun run, String runSetupId) {
        ProductionText.requireStorable(runSetupId, "targetRunSetupId");
        if (runSetupId == null || runSetupId.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Choose the setup to cancel.");
        }
        ProductionRunSetup setup = setups.findById(runSetupId.trim())
            .filter(one -> run.getProductionRunId().equals(one.getProductionRunId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (setup.isCancelled()) {
            throw new BusinessException(ErrorCode.CONFLICT, "This setup was already cancelled by " + setup.getCancelledBy() + ".");
        }
        return setup;
    }

    /** The equipment of a setup a correction would add, checked like a live one (AS1, AS7). */
    String requireCorrectionSetup(ProductionRun run, String equipmentId, Integer minutes) {
        String id = ProductionText.trimToNull(equipmentId, "equipmentId");
        if (id == null) throw new BusinessException(ErrorCode.BAD_REQUEST, "Choose the equipment of the setup to add.");
        if (minutes == null || minutes < 1 || minutes > MAX_MINUTES) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "setupMinutes must be whole minutes from 1 to " + MAX_MINUTES + ".");
        }
        catalog.findProjectEquipment(run.getProjectId(), id)
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "Equipment does not exist in this project."));
        return id;
    }

    void cancelForCorrection(ProductionRun run, String runSetupId, String reason, String actor) {
        ProductionRunSetup setup = findCancellable(run, runSetupId);
        setup.setCancelledYn("Y");
        setup.setCancelledBy(actor);
        setup.setCancelledAt(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
        setup.setCancelReason(reason.length() > MAX_TEXT ? reason.substring(0, MAX_TEXT) : reason);
        setups.saveAndFlush(setup);
    }

    /**
     * A setup added by a correction takes the equipment's rate at the run's original finish, like corrected material at its
     * finish-time price (DECISIONS-2026-10-05 section 1); before the first recorded rate change it is estimated (AS8).
     */
    ProductionRunSetup addForCorrection(ProductionRun run, String correctionLineId, String equipmentId, int minutes, String note,
                                        String actor) {
        EquipmentCostQuery.EquipmentRateAt rate = equipmentCosts.findHourlyRateAt(run.getProjectId(), equipmentId, run.getActualEndAt())
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "Equipment does not exist in this project."));
        ProductionRunSetup setup = new ProductionRunSetup();
        setup.setRunSetupId(ids.generate());
        setup.setProductionRunId(run.getProductionRunId());
        setup.setProjectId(run.getProjectId());
        setup.setEquipmentId(equipmentId);
        setup.setSetupMinutes(minutes);
        setup.setHourlyCost(rate.hourlyCost());
        setup.setHourlyCostVersion(rate.version());
        setup.setSetupCost(rate.hourlyCost() == null ? null
            : rate.hourlyCost().multiply(BigDecimal.valueOf(minutes)).divide(MINUTES_PER_HOUR, SCALE, RoundingMode.HALF_UP));
        setup.setRateBasis(rate.estimated() ? "estimated" : "historical");
        // The correction line is the key, so the same line never adds two setups.
        setup.setRequestId(java.util.UUID.nameUUIDFromBytes(("run-correction-line:" + correctionLineId).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        setup.setNote(note.length() > MAX_TEXT ? note.substring(0, MAX_TEXT) : note);
        setup.setRecordedBy(actor);
        setup.setRecordedAt(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
        setup.setCancelledYn("N");
        return setups.saveAndFlush(setup);
    }

    private ProductionRun lockedRun(String runId) {
        return runs.findForUpdate(runId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private String defaultEquipment(ProductionRun run) {
        return run.getWorkOrderId() == null ? null
            : workOrders.findByWorkOrderIdAndDeletedYn(run.getWorkOrderId(), "N").map(WorkOrder::getEquipmentId).orElse(null);
    }

    private RunSetupCostResponse response(ProductionRun run) {
        Map<String, CatalogEquipmentView> equipment = new HashMap<>();
        catalog.findProjectEquipments(run.getProjectId()).forEach(one -> equipment.put(one.equipmentId(), one));
        int minutes = 0;
        BigDecimal cost = BigDecimal.ZERO;
        boolean complete = true;
        List<RunSetupCostResponse.Line> lines = new ArrayList<>();
        for (ProductionRunSetup row : setups.findAllByProductionRunIdOrderByRecordedAtAscRunSetupIdAsc(run.getProductionRunId())) {
            if (!row.isCancelled()) {
                minutes += row.getSetupMinutes();
                if (row.getSetupCost() == null) {
                    complete = false;
                } else {
                    cost = cost.add(row.getSetupCost());
                }
            }
            CatalogEquipmentView view = equipment.get(row.getEquipmentId());
            String label = view == null ? row.getEquipmentId()
                : view.equipmentCode() != null ? view.equipmentCode() : view.equipmentName();
            lines.add(new RunSetupCostResponse.Line(row.getRunSetupId(), row.getEquipmentId(), label, row.getSetupMinutes(),
                row.getHourlyCost(), row.getHourlyCostVersion(), row.getSetupCost(), row.getNote(), row.getRecordedBy(),
                row.getRecordedAt(), row.isCancelled(), row.getCancelledBy(), row.getCancelledAt(), row.getCancelReason(),
                row.getRateBasis()));
        }
        return new RunSetupCostResponse(run.getProductionRunId(), defaultEquipment(run), minutes,
            cost.setScale(SCALE, RoundingMode.HALF_UP), complete, lines);
    }

    private static String text(String value, String field) {
        String trimmed = ProductionText.trimToNull(value, field);
        if (trimmed != null && trimmed.length() > MAX_TEXT) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, field + " must be at most " + MAX_TEXT + " characters.");
        }
        return trimmed;
    }
}
