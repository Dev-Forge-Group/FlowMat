package org.myweb.flowmat.domain.quality.application;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.domain.quality.api.dto.request.InspectionStandardRequest;
import org.myweb.flowmat.domain.quality.api.dto.request.QualityInspectionCreateRequest;
import org.myweb.flowmat.domain.quality.api.dto.response.InspectionStandardResponse;
import org.myweb.flowmat.domain.quality.api.dto.response.RunQualityChecklistResponse;
import org.myweb.flowmat.domain.quality.domain.entity.InspectionStandard;
import org.myweb.flowmat.domain.quality.domain.entity.QualityInspection;
import org.myweb.flowmat.domain.quality.repository.InspectionStandardRepository;
import org.myweb.flowmat.domain.quality.repository.QualityInspectionRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.myweb.flowmat.domain.quality.application.publicapi.ProductionRunQuery;
import org.myweb.flowmat.domain.quality.application.publicapi.ProductionRunView;

/**
 * Inspection standards (docs/domain/inspection-standard.md, benchmark FM-MFG-004): per item, which checks to run, with
 * what limits, and whether every production run of the item must record them. An inspection that names a standard
 * takes its check, limits and unit from it; a run's checklist shows which of its items' checks are done.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InspectionStandardService {

    public static final List<String> STAGES = List.of("receipt", "production", "any");
    private static final String NOT_DELETED = "N";
    private static final String DELETED = "Y";

    private final InspectionStandardRepository standardRepository;
    private final QualityInspectionRepository inspectionRepository;
    private final CatalogQuery catalogQuery;
    private final ProductionRunQuery productionRunQuery;
    private final ProjectAccess projectAccessService;
    private final IdGenerator idGenerator;

    /** The project's standards, or one item's, by item code and then check. */
    public List<InspectionStandardResponse> list(String projectId, String itemId) {
        String project = required(projectId, "projectId");
        projectAccessService.requireProjectReadAccess(project);
        String item = trimToNull(itemId);
        List<InspectionStandard> standards = standardRepository.findAllByProjectIdAndDeletedYn(project, NOT_DELETED).stream()
            .filter(standard -> item == null || item.equals(standard.getItemId()))
            .toList();
        Map<String, CatalogItemView> items = items(standards.stream().map(InspectionStandard::getItemId).collect(Collectors.toSet()));
        return standards.stream()
            .sorted(Comparator.comparing((InspectionStandard standard) -> code(items.get(standard.getItemId())))
                .thenComparing(standard -> standard.getInspectionType().toLowerCase(Locale.ROOT))
                .thenComparing(standard -> STAGES.indexOf(standard.getStage())))
            .map(standard -> toResponse(standard, items.get(standard.getItemId())))
            .toList();
    }

    @Transactional
    public InspectionStandardResponse create(InspectionStandardRequest request) {
        String projectId = required(request.projectId(), "projectId");
        projectAccessService.requireProjectWriteAccess(projectId);
        CatalogItemView item = catalogQuery.findProjectItem(projectId, required(request.itemId(), "itemId"))
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "The item was not found in this project."));
        InspectionStandard standard = new InspectionStandard();
        standard.setStandardId(idGenerator.generate());
        standard.setProjectId(projectId);
        standard.setItemId(item.itemId());
        standard.setActiveYn("Y");
        standard.setDeletedYn(NOT_DELETED);
        apply(standard, request);
        requireNoOverlap(standard, item);
        standard.setCreatedBy(projectAccessService.requireCurrentUserId());
        return toResponse(standardRepository.saveAndFlush(standard), item);
    }

    @Transactional
    public InspectionStandardResponse update(String standardId, InspectionStandardRequest request) {
        InspectionStandard standard = findLive(standardId);
        projectAccessService.requireProjectWriteAccess(standard.getProjectId());
        if (trimToNull(request.itemId()) != null && !request.itemId().trim().equals(standard.getItemId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "A standard keeps its item; add a new one for the other item.");
        }
        apply(standard, request);
        if (request.active() != null) {
            standard.setActiveYn(request.active() ? "Y" : "N");
        }
        CatalogItemView item = catalogQuery.findItems(List.of(standard.getItemId())).get(standard.getItemId());
        requireNoOverlap(standard, item);
        standard.setUpdatedBy(projectAccessService.requireCurrentUserId());
        return toResponse(standardRepository.saveAndFlush(standard), item);
    }

    /** Inspections that followed the standard keep pointing at it; it only stops being offered and checked. */
    @Transactional
    public void delete(String standardId) {
        InspectionStandard standard = findLive(standardId);
        projectAccessService.requireProjectWriteAccess(standard.getProjectId());
        standard.setDeletedYn(DELETED);
        standard.setUpdatedBy(projectAccessService.requireCurrentUserId());
        standardRepository.save(standard);
    }

    /**
     * The active production checks of the items this run makes (its target item and every output it recorded, cancelled
     * recordings left out), each with the latest inspection of the run that followed the standard or recorded the same
     * check on the same item. Required checks first.
     */
    public RunQualityChecklistResponse checklist(String productionRunId) {
        ProductionRunView run = productionRunQuery.findRun(required(productionRunId, "productionRunId"))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        projectAccessService.requireProjectReadAccess(run.projectId());
        Set<String> outputs = new LinkedHashSet<>();
        if (run.targetItemId() != null) {
            outputs.add(run.targetItemId());
        }
        productionRunQuery.findRecordings(run.productionRunId()).stream()
            .filter(recording -> "output".equals(recording.direction()))
            .forEach(recording -> outputs.add(recording.itemId()));
        List<InspectionStandard> standards = outputs.isEmpty() ? List.of()
            : standardRepository.findAllByItemIdInAndDeletedYn(outputs, NOT_DELETED).stream()
                .filter(standard -> "Y".equals(standard.getActiveYn()) && !"receipt".equals(standard.getStage()))
                .toList();
        List<QualityInspection> inspections = inspectionRepository.findAllByProductionRunIdOrderByInspectedAtDesc(run.productionRunId());
        Map<String, CatalogItemView> items = items(outputs);
        List<RunQualityChecklistResponse.Line> lines = standards.stream()
            .sorted(Comparator.comparing((InspectionStandard standard) -> !"Y".equals(standard.getRequiredYn()))
                .thenComparing(standard -> code(items.get(standard.getItemId())))
                .thenComparing(standard -> standard.getInspectionType().toLowerCase(Locale.ROOT)))
            .map(standard -> line(standard, items.get(standard.getItemId()), latest(standard, inspections)))
            .toList();
        int required = (int) lines.stream().filter(RunQualityChecklistResponse.Line::required).count();
        int requiredPassed = (int) lines.stream().filter(line -> line.required() && QualityInspection.PASS.equals(line.status())).count();
        int requiredMissing = (int) lines.stream().filter(line -> line.required() && "missing".equals(line.status())).count();
        int failed = (int) lines.stream().filter(line -> QualityInspection.FAIL.equals(line.status())).count();
        return new RunQualityChecklistResponse(run.productionRunId(), required, requiredPassed, requiredMissing, failed, lines);
    }

    /** The standard an inspection names: live, active and in the project. */
    InspectionStandard requireUsable(String projectId, String standardId) {
        InspectionStandard standard = standardRepository.findByStandardIdAndDeletedYn(standardId, NOT_DELETED)
            .filter(found -> projectId.equals(found.getProjectId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "The inspection standard was not found in this project."));
        if (!"Y".equals(standard.getActiveYn())) {
            throw new BusinessException(ErrorCode.CONFLICT, "The " + standard.getInspectionType() + " standard is inactive.");
        }
        return standard;
    }

    /**
     * The request with the standard's check, limits and unit, and the standard named. A request that sends a different
     * check, other limits or another unit is refused rather than quietly overridden.
     */
    static QualityInspectionCreateRequest applyTo(InspectionStandard standard, QualityInspectionCreateRequest request) {
        if (!standard.getInspectionType().equalsIgnoreCase(request.inspectionType().trim())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "This standard is for " + standard.getInspectionType()
                + "; record that check or leave the standard out.");
        }
        if (differs(request.standardMin(), standard.getStandardMin()) || differs(request.standardMax(), standard.getStandardMax())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "The standard sets the limits; leave them empty or send the same.");
        }
        String unit = trimToNull(request.unit());
        if (unit != null && standard.getUnit() != null && !unit.equalsIgnoreCase(standard.getUnit())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "The standard measures in " + standard.getUnit() + ".");
        }
        return new QualityInspectionCreateRequest(
            request.projectId(), request.productionRunId(), request.lotId(), request.itemId(),
            standard.getInspectionType(), request.result(), request.measuredValue(),
            standard.getStandardMin(), standard.getStandardMax(),
            standard.getUnit() != null ? standard.getUnit() : unit,
            request.note(), request.quarantineLot(), standard.getStandardId());
    }

    private void apply(InspectionStandard standard, InspectionStandardRequest request) {
        standard.setInspectionType(required(request.inspectionType(), "inspectionType"));
        String stage = trimToNull(request.stage()) == null ? "production" : request.stage().trim().toLowerCase(Locale.ROOT);
        if (!STAGES.contains(stage)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "stage must be one of " + String.join(", ", STAGES) + ".");
        }
        standard.setStage(stage);
        BigDecimal standardMin = QualityNumbers.requireStorable(request.standardMin(), "standardMin");
        BigDecimal standardMax = QualityNumbers.requireStorable(request.standardMax(), "standardMax");
        if (standardMin != null && standardMax != null && standardMin.compareTo(standardMax) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "The lower limit is above the upper limit.");
        }
        standard.setStandardMin(standardMin);
        standard.setStandardMax(standardMax);
        standard.setUnit(trimToNull(request.unit()));
        standard.setRequiredYn(Boolean.TRUE.equals(request.required()) ? "Y" : "N");
        standard.setNote(trimToNull(request.note()));
    }

    /** One standard per item and check at a time: two stages overlap when they are equal or either is "any". */
    private void requireNoOverlap(InspectionStandard candidate, CatalogItemView item) {
        for (InspectionStandard other : standardRepository.findAllByItemIdAndDeletedYn(candidate.getItemId(), NOT_DELETED)) {
            boolean overlaps = other.getStage().equals(candidate.getStage())
                || "any".equals(other.getStage()) || "any".equals(candidate.getStage());
            if (!other.getStandardId().equals(candidate.getStandardId())
                && other.getInspectionType().equalsIgnoreCase(candidate.getInspectionType()) && overlaps) {
                throw new BusinessException(ErrorCode.CONFLICT, code(item) + " already has a " + other.getInspectionType()
                    + " standard for " + other.getStage() + ".");
            }
        }
    }

    private static QualityInspection latest(InspectionStandard standard, List<QualityInspection> newestFirst) {
        return newestFirst.stream()
            .filter(inspection -> standard.getStandardId().equals(inspection.getStandardId())
                || (inspection.getStandardId() == null && standard.getItemId().equals(inspection.getItemId())
                    && standard.getInspectionType().equalsIgnoreCase(inspection.getInspectionType())))
            .findFirst()
            .orElse(null);
    }

    private static RunQualityChecklistResponse.Line line(InspectionStandard standard, CatalogItemView item, QualityInspection inspection) {
        return new RunQualityChecklistResponse.Line(
            standard.getStandardId(),
            standard.getItemId(),
            item == null ? null : item.itemCode(),
            item == null ? null : item.itemName(),
            standard.getInspectionType(),
            standard.getStage(),
            standard.getStandardMin(),
            standard.getStandardMax(),
            standard.getUnit(),
            "Y".equals(standard.getRequiredYn()),
            inspection == null ? "missing" : inspection.getResultStatus(),
            inspection == null ? null : inspection.getInspectionId(),
            inspection == null ? null : inspection.getMeasuredValue(),
            inspection == null ? null : inspection.getInspectedAt()
        );
    }

    private static InspectionStandardResponse toResponse(InspectionStandard standard, CatalogItemView item) {
        return new InspectionStandardResponse(
            standard.getStandardId(),
            standard.getProjectId(),
            standard.getItemId(),
            item == null ? null : item.itemCode(),
            item == null ? null : item.itemName(),
            standard.getInspectionType(),
            standard.getStage(),
            standard.getStandardMin(),
            standard.getStandardMax(),
            standard.getUnit(),
            "Y".equals(standard.getRequiredYn()),
            "Y".equals(standard.getActiveYn()),
            standard.getNote()
        );
    }

    private Map<String, CatalogItemView> items(Set<String> itemIds) {
        return catalogQuery.findItems(itemIds);
    }

    private InspectionStandard findLive(String standardId) {
        return standardRepository.findByStandardIdAndDeletedYn(required(standardId, "standardId"), NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private static String code(CatalogItemView item) {
        return item == null ? "" : item.itemCode();
    }

    private static boolean differs(BigDecimal sent, BigDecimal standard) {
        return sent != null && (standard == null || sent.compareTo(standard) != 0);
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
