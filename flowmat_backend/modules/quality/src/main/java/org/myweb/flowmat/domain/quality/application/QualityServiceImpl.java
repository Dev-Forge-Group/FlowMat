package org.myweb.flowmat.domain.quality.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockMovementCommand;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockMovementCommand.Kind;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockMovementCommand.StockMovement;
import org.myweb.flowmat.domain.inventory.application.publicapi.LotQuery;
import org.myweb.flowmat.domain.inventory.application.publicapi.LotView;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockRecordQuery;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.quality.api.dto.request.DefectCreateRequest;
import org.myweb.flowmat.domain.quality.api.dto.request.DefectResolveRequest;
import org.myweb.flowmat.domain.quality.api.dto.request.QualityInspectionCreateRequest;
import org.myweb.flowmat.domain.quality.api.dto.response.DefectResponse;
import org.myweb.flowmat.domain.quality.api.dto.response.QualityInspectionResponse;
import org.myweb.flowmat.domain.quality.domain.entity.DefectLog;
import org.myweb.flowmat.domain.quality.domain.entity.InspectionStandard;
import org.myweb.flowmat.domain.quality.domain.entity.QualityInspection;
import org.myweb.flowmat.domain.quality.repository.DefectLogRepository;
import org.myweb.flowmat.domain.quality.repository.QualityInspectionRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inspections and defects (docs/domain/quality-inspection.md). Both are records only: an inspection moves stock only
 * when a failed one quarantines its LOT, and that goes through the stock command path like any quarantine.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QualityServiceImpl implements QualityService {

    private static final Set<String> SEVERITIES = Set.of("minor", "major", "critical");

    private final QualityInspectionRepository inspectionRepository;
    private final DefectLogRepository defectLogRepository;
    private final ProjectAccessService projectAccessService;
    private final ProductionRunQuery productionRunQuery;
    private final LotQuery lotQuery;
    private final CatalogQuery catalogQuery;
    private final StockRecordQuery stockRecordQuery;
    private final StockMovementCommand stockMovements;
    private final IdGenerator idGenerator;
    private final InspectionStandardService inspectionStandardService;

    @Override
    public List<QualityInspectionResponse> listInspections(String projectId, String productionRunId, String lotId, String itemId) {
        projectAccessService.requireProjectReadAccess(projectId);
        String runFilter = trimToNull(productionRunId);
        String lotFilter = trimToNull(lotId);
        List<QualityInspection> found = runFilter != null
            ? inspectionRepository.findAllByProductionRunIdOrderByInspectedAtDesc(runFilter)
            : lotFilter != null
                ? inspectionRepository.findAllByLotIdOrderByInspectedAtDesc(lotFilter)
                : inspectionRepository.findAllByProjectIdOrderByInspectedAtDesc(projectId);
        List<QualityInspection> inspections = found.stream()
            .filter(inspection -> projectId.equals(inspection.getProjectId()))
            .filter(inspection -> lotFilter == null || lotFilter.equals(inspection.getLotId()))
            .filter(inspection -> trimToNull(itemId) == null || itemId.trim().equals(inspection.getItemId()))
            .toList();
        Names names = names(
            inspections.stream().map(QualityInspection::getProductionRunId),
            inspections.stream().map(QualityInspection::getItemId),
            inspections.stream().map(QualityInspection::getLotId));
        return inspections.stream().map(inspection -> toResponse(inspection, names)).toList();
    }

    @Override
    @Transactional
    public QualityInspectionResponse recordInspection(QualityInspectionCreateRequest request) {
        String projectId = request.projectId().trim();
        projectAccessService.requireProjectWriteAccess(projectId);
        String actor = projectAccessService.requireCurrentUserId();
        // A standard sets the check, its limits and unit, and names the item when nothing else does
        // (docs/domain/inspection-standard.md).
        InspectionStandard standard = trimToNull(request.standardId()) == null ? null
            : inspectionStandardService.requireUsable(projectId, request.standardId().trim());
        String itemId = standard != null && trimToNull(request.lotId()) == null && trimToNull(request.itemId()) == null
            ? standard.getItemId() : request.itemId();
        Target target = resolveTarget(projectId, request.productionRunId(), request.lotId(), itemId);
        if (target.run() == null && target.lot() == null && target.item() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Pick what was inspected: a LOT, a run or an item.");
        }
        if (standard != null) {
            if (target.item() == null || !standard.getItemId().equals(target.item().itemId())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "This standard is for a different item.");
            }
            request = InspectionStandardService.applyTo(standard, request);
        }
        BigDecimal measuredValue = QualityNumbers.requireStorable(request.measuredValue(), "measuredValue");
        BigDecimal standardMin = QualityNumbers.requireStorable(request.standardMin(), "standardMin");
        BigDecimal standardMax = QualityNumbers.requireStorable(request.standardMax(), "standardMax");
        String result = decideResult(request);
        boolean quarantine = Boolean.TRUE.equals(request.quarantineLot());
        if (quarantine && !QualityInspection.FAIL.equals(result)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Only a failed inspection quarantines its LOT.");
        }
        if (quarantine && target.lot() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Pick the LOT to quarantine.");
        }

        QualityInspection inspection = new QualityInspection();
        inspection.setInspectionId(idGenerator.generate());
        inspection.setProjectId(projectId);
        inspection.setProductionRunId(target.run() == null ? null : target.run().productionRunId());
        inspection.setItemId(target.item() == null ? null : target.item().itemId());
        inspection.setLotId(target.lot() == null ? null : target.lot().lotId());
        inspection.setInspectionType(request.inspectionType().trim());
        inspection.setResultStatus(result);
        inspection.setMeasuredValue(measuredValue);
        inspection.setStandardMin(standardMin);
        inspection.setStandardMax(standardMax);
        inspection.setUnit(trimToNull(request.unit()));
        inspection.setNote(trimToNull(request.note()));
        inspection.setInspectedBy(actor);
        inspection.setInspectedAt(OffsetDateTime.now());
        inspection.setStandardId(standard == null ? null : standard.getStandardId());
        QualityInspection saved = inspectionRepository.saveAndFlush(inspection);
        if (quarantine) {
            quarantine(target.lot(), saved, actor);
        }
        // Read the names after the quarantine so the LOT status in the answer is the new one.
        return toResponse(saved, names(
            Stream.of(saved.getProductionRunId()), Stream.of(saved.getItemId()), Stream.of(saved.getLotId())));
    }

    @Override
    public List<DefectResponse> listDefects(String projectId, String productionRunId, String lotId, String itemId, boolean openOnly) {
        projectAccessService.requireProjectReadAccess(projectId);
        String runFilter = trimToNull(productionRunId);
        String lotFilter = trimToNull(lotId);
        List<DefectLog> found = runFilter != null
            ? defectLogRepository.findAllByProductionRunIdOrderByLoggedAtDesc(runFilter)
            : lotFilter != null
                ? defectLogRepository.findAllByLotIdOrderByLoggedAtDesc(lotFilter)
                : defectLogRepository.findAllByProjectIdOrderByLoggedAtDesc(projectId);
        List<DefectLog> defects = found.stream()
            .filter(defect -> projectId.equals(defect.getProjectId()))
            .filter(defect -> lotFilter == null || lotFilter.equals(defect.getLotId()))
            .filter(defect -> trimToNull(itemId) == null || itemId.trim().equals(defect.getItemId()))
            .filter(defect -> !openOnly || !defect.isResolved())
            .toList();
        Names names = names(
            defects.stream().map(DefectLog::getProductionRunId),
            defects.stream().map(DefectLog::getItemId),
            defects.stream().map(DefectLog::getLotId));
        return defects.stream().map(defect -> toResponse(defect, names)).toList();
    }

    @Override
    @Transactional
    public DefectResponse logDefect(DefectCreateRequest request) {
        String projectId = request.projectId().trim();
        projectAccessService.requireProjectWriteAccess(projectId);
        String actor = projectAccessService.requireCurrentUserId();
        QualityInspection inspection = null;
        if (trimToNull(request.inspectionId()) != null) {
            inspection = inspectionRepository.findById(request.inspectionId().trim())
                .filter(found -> projectId.equals(found.getProjectId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "The inspection was not found in this project."));
        }
        Target target = resolveTarget(projectId,
            inherit("run", request.productionRunId(), inspection == null ? null : inspection.getProductionRunId()),
            inherit("LOT", request.lotId(), inspection == null ? null : inspection.getLotId()),
            inherit("item", request.itemId(), inspection == null ? null : inspection.getItemId()));
        if (target.item() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Pick the item or LOT the defect was found in.");
        }
        BigDecimal quantity = QualityNumbers.requireStorable(request.quantity(), "quantity");
        if (quantity == null || quantity.signum() <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "The defective quantity must be more than 0.");
        }
        String severity = trimToNull(request.severity()) == null ? "minor" : request.severity().trim().toLowerCase(Locale.ROOT);
        if (!SEVERITIES.contains(severity)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Severity must be minor, major or critical.");
        }

        OffsetDateTime now = OffsetDateTime.now();
        DefectLog defect = new DefectLog();
        defect.setDefectLogId(idGenerator.generate());
        defect.setProjectId(projectId);
        defect.setInspectionId(inspection == null ? null : inspection.getInspectionId());
        defect.setProductionRunId(target.run() == null ? null : target.run().productionRunId());
        defect.setItemId(target.item().itemId());
        defect.setLotId(target.lot() == null ? null : target.lot().lotId());
        defect.setDefectType(request.defectType().trim());
        defect.setQuantity(quantity);
        defect.setSeverity(severity);
        defect.setReason(trimToNull(request.reason()));
        defect.setResolvedYn("N");
        defect.setDetectedAt(now);
        defect.setLoggedAt(now);
        defect.setLoggedBy(actor);
        DefectLog saved = defectLogRepository.save(defect);
        return toResponse(saved, names(
            Stream.of(saved.getProductionRunId()), Stream.of(saved.getItemId()), Stream.of(saved.getLotId())));
    }

    @Override
    @Transactional
    public DefectResponse resolveDefect(String defectLogId, DefectResolveRequest request) {
        DefectLog defect = defectLogRepository.findForUpdate(defectLogId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        projectAccessService.requireProjectWriteAccess(defect.getProjectId());
        if (defect.isResolved()) {
            throw new BusinessException(ErrorCode.CONFLICT, "This defect was already resolved.");
        }
        String actionTaken = trimToNull(request.actionTaken());
        if (actionTaken == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Say what was done about the defect.");
        }
        String scrapInventoryId = trimToNull(request.scrapInventoryId());
        if ((scrapInventoryId == null) != (request.scrapQuantity() == null)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "To scrap stock, give both the stock record and the quantity.");
        }
        if (scrapInventoryId != null) {
            actionTaken = actionTaken + " (" + scrap(defect, scrapInventoryId, request.scrapQuantity()) + ")";
        }
        defect.setActionTaken(actionTaken);
        defect.setResolvedYn("Y");
        defect.setResolvedAt(OffsetDateTime.now());
        defect.setResolvedBy(projectAccessService.requireCurrentUserId());
        DefectLog saved = defectLogRepository.save(defect);
        return toResponse(saved, names(
            Stream.of(saved.getProductionRunId()), Stream.of(saved.getItemId()), Stream.of(saved.getLotId())));
    }

    /**
     * Writes defective stock off: a decreasing adjustment that points at the defect. An adjustment, not an issue, so that
     * quarantined stock (where defects usually sit) can be scrapped. Returns what was done, for the defect's action text.
     */
    private String scrap(DefectLog defect, String inventoryId, BigDecimal quantity) {
        if (quantity.signum() <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Scrap more than 0.");
        }
        StockRecordQuery.StockIdentity row = stockRecordQuery.findProjectStock(defect.getProjectId(), inventoryId)
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "The stock record to scrap was not found in this project."));
        if (!defect.getItemId().equals(row.itemId())
            || (defect.getLotId() != null && !defect.getLotId().equals(row.lotId()))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Scrap from stock of the defective item"
                + (defect.getLotId() != null ? " and LOT" : "") + ".");
        }
        stockMovements.apply(new StockMovement(
            row.inventoryId(),
            Kind.ADJUSTMENT,
            quantity.negate(),
            BigDecimal.ZERO,
            "defect_log",
            defect.getDefectLogId(),
            "Scrapped: " + defect.getDefectType(),
            null,
            projectAccessService.requireCurrentUserId()
        ));
        return "scrapped " + plain(quantity) + " from " + (row.location() == null ? "no location" : row.location());
    }

    // ---- target ----

    private record Target(ProductionRunView run, LotView lot, CatalogItemView item) {
    }

    /** Checks that the run, LOT and item are in the project and fit together; the item follows from the LOT. */
    private Target resolveTarget(String projectId, String productionRunId, String lotId, String itemId) {
        ProductionRunView run = null;
        if (trimToNull(productionRunId) != null) {
            run = productionRunQuery.findProjectRun(projectId, productionRunId.trim())
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "The run was not found in this project."));
        }
        LotView lot = null;
        if (trimToNull(lotId) != null) {
            lot = lotQuery.findProjectLot(projectId, lotId.trim())
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "The LOT was not found in this project."));
        }
        String requestedItemId = trimToNull(itemId);
        if (lot != null && requestedItemId != null && !requestedItemId.equals(lot.itemId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "LOT " + lot.lotNo() + " is a LOT of a different item.");
        }
        String effectiveItemId = requestedItemId != null ? requestedItemId : lot == null ? null : lot.itemId();
        CatalogItemView item = null;
        if (effectiveItemId != null) {
            item = catalogQuery.findProjectItem(projectId, effectiveItemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "The item was not found in this project."));
        }
        if (run != null && lot != null && !lotInRun(run, lot)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "LOT " + lot.lotNo() + " was not used or made by run " + run.runNumber() + ".");
        }
        if (run != null && lot == null && item != null && !itemInRun(run, item)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "Item " + item.itemCode() + " was not used or made by run " + run.runNumber() + ".");
        }
        return new Target(run, lot, item);
    }

    private boolean lotInRun(ProductionRunView run, LotView lot) {
        return run.productionRunId().equals(lot.productionRunId())
            || productionRunQuery.findRecordings(run.productionRunId()).stream()
                .anyMatch(recording -> lot.lotId().equals(recording.lotId()));
    }

    private boolean itemInRun(ProductionRunView run, CatalogItemView item) {
        return item.itemId().equals(run.targetItemId())
            || productionRunQuery.findRecordings(run.productionRunId()).stream()
                .anyMatch(recording -> item.itemId().equals(recording.itemId()));
    }

    /** A defect found by an inspection is about what that inspection looked at; a different value is a mistake. */
    private static String inherit(String what, String requested, String fromInspection) {
        String value = trimToNull(requested);
        if (value != null && fromInspection != null && !value.equals(fromInspection)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "The " + what + " differs from the inspection's " + what + ".");
        }
        return value != null ? value : fromInspection;
    }

    // ---- result and quarantine ----

    /** A measured value with a limit decides the result; otherwise the inspector gives it. */
    private static String decideResult(QualityInspectionCreateRequest request) {
        BigDecimal value = request.measuredValue();
        BigDecimal min = request.standardMin();
        BigDecimal max = request.standardMax();
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "The lower limit " + plain(min) + " is above the upper limit " + plain(max) + ".");
        }
        String requested = trimToNull(request.result()) == null ? null : request.result().trim().toLowerCase(Locale.ROOT);
        if (requested != null && !QualityInspection.PASS.equals(requested) && !QualityInspection.FAIL.equals(requested)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "The result must be pass or fail.");
        }
        if (value != null && (min != null || max != null)) {
            boolean within = (min == null || value.compareTo(min) >= 0) && (max == null || value.compareTo(max) <= 0);
            String measured = within ? QualityInspection.PASS : QualityInspection.FAIL;
            if (requested != null && !requested.equals(measured)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "The measured value " + plain(value)
                    + (within ? " is within" : " is outside") + " the limits, so the result is " + measured + ".");
            }
            return measured;
        }
        if (requested == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Give the result (pass or fail), or a measured value with a limit.");
        }
        return requested;
    }

    /** Quarantines the whole LOT through the stock command path, pointing the movement at the inspection. */
    private void quarantine(LotView lot, QualityInspection inspection, String actor) {
        if (lot.quarantined()) {
            return;
        }
        if (lot.closed()) {
            throw new BusinessException(ErrorCode.CONFLICT, "LOT " + lot.lotNo() + " is closed, so it cannot be quarantined.");
        }
        List<String> rows = stockRecordQuery.findLotStockIds(lot.lotId());
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT, "LOT " + lot.lotNo() + " has no stock record to quarantine.");
        }
        stockMovements.apply(new StockMovement(
            rows.get(0),
            Kind.QUARANTINE,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            "quality_inspection",
            inspection.getInspectionId(),
            "Failed inspection: " + inspection.getInspectionType(),
            null,
            actor
        ));
    }

    // ---- responses ----

    private record Names(
        Map<String, ProductionRunView> runs,
        Map<String, CatalogItemView> items,
        Map<String, LotView> lots
    ) {
    }

    private Names names(Stream<String> runIds, Stream<String> itemIds, Stream<String> lotIds) {
        Map<String, ProductionRunView> runs = productionRunQuery.findRuns(distinct(runIds));
        Map<String, CatalogItemView> items = catalogQuery.findItems(distinct(itemIds));
        Map<String, LotView> lots = lotQuery.findLots(distinct(lotIds));
        return new Names(runs, items, lots);
    }

    private QualityInspectionResponse toResponse(QualityInspection inspection, Names names) {
        ProductionRunView run = names.runs().get(inspection.getProductionRunId());
        CatalogItemView item = names.items().get(inspection.getItemId());
        LotView lot = names.lots().get(inspection.getLotId());
        return new QualityInspectionResponse(
            inspection.getInspectionId(),
            inspection.getProjectId(),
            inspection.getProductionRunId(),
            run == null ? null : run.runNumber(),
            inspection.getItemId(),
            item == null ? null : item.itemCode(),
            item == null ? null : item.itemName(),
            inspection.getLotId(),
            lot == null ? null : lot.lotNo(),
            lot == null ? null : lot.lotStatus(),
            inspection.getInspectionType(),
            inspection.getResultStatus(),
            inspection.getMeasuredValue(),
            inspection.getStandardMin(),
            inspection.getStandardMax(),
            inspection.getUnit(),
            inspection.getNote(),
            inspection.getInspectedBy(),
            inspection.getInspectedAt(),
            inspection.getStandardId()
        );
    }

    private DefectResponse toResponse(DefectLog defect, Names names) {
        ProductionRunView run = names.runs().get(defect.getProductionRunId());
        CatalogItemView item = names.items().get(defect.getItemId());
        LotView lot = names.lots().get(defect.getLotId());
        return new DefectResponse(
            defect.getDefectLogId(),
            defect.getProjectId(),
            defect.getInspectionId(),
            defect.getProductionRunId(),
            run == null ? null : run.runNumber(),
            defect.getItemId(),
            item == null ? null : item.itemCode(),
            item == null ? null : item.itemName(),
            defect.getLotId(),
            lot == null ? null : lot.lotNo(),
            defect.getDefectType(),
            defect.getQuantity(),
            item == null ? null : item.unitCode(),
            defect.getSeverity(),
            defect.getReason(),
            defect.isResolved(),
            defect.getActionTaken(),
            defect.getLoggedBy(),
            defect.getLoggedAt(),
            defect.getResolvedBy(),
            defect.getResolvedAt()
        );
    }

    private static Collection<String> distinct(Stream<String> ids) {
        return ids.filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
