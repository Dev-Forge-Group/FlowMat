package org.myweb.flowmat.domain.bom.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.bom.api.dto.request.BomCreateRequest;
import org.myweb.flowmat.domain.bom.api.dto.request.BomLineCreateRequest;
import org.myweb.flowmat.domain.bom.api.dto.request.BomUpdateRequest;
import org.myweb.flowmat.domain.bom.api.dto.response.BomLineResponse;
import org.myweb.flowmat.domain.bom.api.dto.response.BomRequirementResponse;
import org.myweb.flowmat.domain.bom.api.dto.response.BomResponse;
import org.myweb.flowmat.domain.bom.api.dto.response.BomWhereUsedResponse;
import org.myweb.flowmat.domain.bom.domain.entity.BomHeader;
import org.myweb.flowmat.domain.bom.domain.entity.BomLine;
import org.myweb.flowmat.domain.bom.domain.enums.BomStatus;
import org.myweb.flowmat.domain.bom.repository.BomHeaderRepository;
import org.myweb.flowmat.domain.bom.repository.BomLineRepository;
import org.myweb.flowmat.domain.catalog.application.ItemStatusRule;
import org.myweb.flowmat.domain.catalog.application.UnitConverter;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BomServiceImpl implements BomService {

    static final String NOT_DELETED = "N";
    private static final String DELETED = "Y";
    /** Requirement factors keep this many decimals before the final 4-decimal stock quantity. */
    private static final int FACTOR_SCALE = 12;
    private static final int STOCK_SCALE = 4;

    private final BomHeaderRepository bomHeaderRepository;
    private final BomLineRepository bomLineRepository;
    private final CatalogQuery catalogQuery;
    private final BomRevisionLock revisionLock;
    private final UnitConverter unitConverter;
    private final ProjectAccessService projectAccessService;
    private final IdGenerator idGenerator;
    private final ProjectCalendarQuery projectCalendar;

    @Override
    public List<BomResponse> listBoms(String projectId, String targetItemId) {
        if (projectId == null || projectId.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "projectId is required.");
        }
        projectAccessService.requireProjectReadAccess(projectId.trim());
        List<BomHeader> headers = targetItemId == null || targetItemId.isBlank()
            ? bomHeaderRepository.findAllByProjectIdAndDeletedYnOrderByTargetItemIdAscBomVersionDesc(projectId.trim(), NOT_DELETED)
            : bomHeaderRepository.findAllByProjectIdAndTargetItemIdAndDeletedYnOrderByBomVersionDesc(
                projectId.trim(), targetItemId.trim(), NOT_DELETED);
        if (headers.isEmpty()) {
            return List.of();
        }
        // One query for every BOM's lines instead of one per BOM, in the order lines(bomId) gives.
        Map<String, List<BomLine>> linesByBom = bomLineRepository
            .findAllByBomIdIn(headers.stream().map(BomHeader::getBomId).toList())
            .stream()
            .sorted(Comparator.comparing(BomLine::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(BomLine::getBomLineId))
            .collect(Collectors.groupingBy(BomLine::getBomId));
        return headers.stream().map(header -> toResponse(header, linesByBom.getOrDefault(header.getBomId(), List.of()))).toList();
    }

    @Override
    public List<BomWhereUsedResponse> whereUsed(String projectId, String itemId) {
        if (projectId == null || projectId.isBlank() || itemId == null || itemId.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "projectId and itemId are required.");
        }
        String project = projectId.trim();
        projectAccessService.requireProjectReadAccess(project);
        List<BomLine> lines = bomLineRepository.findAllByChildItemId(itemId.trim());
        Map<String, BomHeader> headers = bomHeaderRepository.findAllById(lines.stream().map(BomLine::getBomId).distinct().toList())
            .stream()
            .filter(header -> project.equals(header.getProjectId()) && NOT_DELETED.equals(header.getDeletedYn()))
            .collect(Collectors.toMap(BomHeader::getBomId, Function.identity()));
        Map<String, CatalogItemView> targets = catalogQuery.findItems(
            headers.values().stream().map(BomHeader::getTargetItemId).distinct().toList());
        return lines.stream()
            .filter(line -> headers.containsKey(line.getBomId()))
            .map(line -> {
                BomHeader header = headers.get(line.getBomId());
                CatalogItemView target = targets.get(header.getTargetItemId());
                return new BomWhereUsedResponse(
                    header.getBomId(),
                    header.getBomName(),
                    header.getBomVersion(),
                    header.getBomStatus(),
                    header.getTargetItemId(),
                    target == null ? null : target.itemCode(),
                    target == null ? null : target.itemName(),
                    header.getBaseQuantity(),
                    header.getBaseUnit(),
                    line.getBomLineId(),
                    line.getQuantity(),
                    line.getUnit(),
                    line.getScrapRate(),
                    line.getLineType() == null ? BomTree.MATERIAL : line.getLineType()
                );
            })
            .sorted(Comparator.comparingInt((BomWhereUsedResponse used) -> statusRank(used.bomStatus()))
                .thenComparing(used -> used.targetItemCode() == null ? "" : used.targetItemCode())
                .thenComparing(BomWhereUsedResponse::bomVersion, Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    /** Approved revisions matter most for impact; retired ones are history. */
    private static int statusRank(String status) {
        return switch (BomStatus.fromCode(status)) {
            case APPROVED -> 0;
            case PENDING_APPROVAL -> 1;
            case DRAFT -> 2;
            case RETIRED -> 3;
        };
    }

    @Override
    public BomResponse getBom(String bomId) {
        BomHeader header = findBom(bomId);
        projectAccessService.requireProjectReadAccess(header.getProjectId());
        return toResponse(header);
    }

    @Override
    @Transactional
    public BomResponse createBom(BomCreateRequest request) {
        String projectId = request.projectId().trim();
        projectAccessService.requireProjectWriteAccess(projectId);
        CatalogItemView target = findProjectItem(request.targetItemId(), projectId);
        requireActive(target, "give it a BOM");
        revisionLock.lockItem(projectId, target.itemId());
        target = lockReferenceItem(request.targetItemId(), projectId);
        requireActive(target, "give it a BOM");
        if (bomHeaderRepository.findTopByProjectIdAndTargetItemIdAndDeletedYnOrderByBomVersionDesc(
            projectId, target.itemId(), NOT_DELETED).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                target.itemCode() + " already has a BOM. Create a new revision of it instead.");
        }
        requirePositive(request.baseQuantity(), "Base quantity");

        BomHeader header = new BomHeader();
        header.setBomId(idGenerator.generate());
        header.setProjectId(projectId);
        header.setTargetItemId(target.itemId());
        header.setBomName(request.bomName().trim());
        header.setBomVersion(1);
        header.setBaseQuantity(request.baseQuantity());
        header.setBaseUnit(request.baseUnit().trim());
        header.setBomStatus(BomStatus.DRAFT.code());
        header.setApprovalStatus("draft");
        header.setNote(trimToNull(request.note()));
        header.setCreatedBy(projectAccessService.requireCurrentUserId());
        header.setDeletedYn(NOT_DELETED);
        return toResponse(bomHeaderRepository.save(header));
    }

    @Override
    @Transactional
    public BomResponse updateBom(String bomId, BomUpdateRequest request) {
        BomHeader header = findEditableBom(bomId);
        if (request.bomName() != null && !request.bomName().isBlank()) {
            header.setBomName(request.bomName().trim());
        }
        if (request.baseQuantity() != null) {
            requirePositive(request.baseQuantity(), "Base quantity");
            header.setBaseQuantity(request.baseQuantity());
        }
        if (request.baseUnit() != null && !request.baseUnit().isBlank()) {
            header.setBaseUnit(request.baseUnit().trim());
        }
        if (request.note() != null) {
            header.setNote(trimToNull(request.note()));
        }
        header.setUpdatedBy(projectAccessService.requireCurrentUserId());
        return toResponse(bomHeaderRepository.save(header));
    }

    @Override
    @Transactional
    public void deleteBom(String bomId) {
        BomHeader header = findEditableBom(bomId);
        header.setDeletedYn(DELETED);
        bomHeaderRepository.save(header);
    }

    @Override
    @Transactional
    public BomResponse addLine(String bomId, BomLineCreateRequest request) {
        BomHeader header = findEditableBom(bomId);
        CatalogItemView child = findProjectItem(request.childItemId(), header.getProjectId());
        child = lockReferenceItem(request.childItemId(), header.getProjectId());
        requireActive(child, "use it in a BOM");
        requirePositive(request.quantity(), "Material quantity");

        BomLine line = new BomLine();
        line.setBomLineId(idGenerator.generate());
        line.setBomId(header.getBomId());
        line.setChildItemId(child.itemId());
        line.setLineType(lineType(request.lineType()));
        boolean phantom = Boolean.TRUE.equals(request.phantom());
        if (phantom && !BomTree.MATERIAL.equals(line.getLineType())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Only a material line can be a phantom.");
        }
        line.setPhantomYn(phantom ? "Y" : "N");
        line.setQuantity(request.quantity());
        line.setUnit(request.unit().trim());
        line.setScrapRate(request.scrapRate() != null ? request.scrapRate() : BigDecimal.ZERO);
        line.setOptionalYn("Y".equalsIgnoreCase(request.optionalYn()) ? "Y" : "N");
        line.setSubstituteGroup(trimToNull(request.substituteGroup()));
        line.setSortOrder(request.sortOrder() != null
            ? request.sortOrder()
            : bomLineRepository.findAllByBomIdOrderBySortOrderAscBomLineIdAsc(header.getBomId()).size() + 1);
        line.setNote(trimToNull(request.note()));
        line.setCreatedBy(projectAccessService.requireCurrentUserId());
        bomLineRepository.save(line);
        return toResponse(header);
    }

    @Override
    @Transactional
    public BomResponse deleteLine(String bomId, String bomLineId) {
        BomHeader header = findEditableBom(bomId);
        BomLine line = bomLineRepository.findById(bomLineId)
            .filter(found -> found.getBomId().equals(header.getBomId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        bomLineRepository.delete(line);
        return toResponse(header);
    }

    @Override
    @Transactional
    public BomResponse createRevision(String bomId) {
        return createRevision(bomId, null);
    }

    @Override
    @Transactional
    public BomResponse createRevision(String bomId, UUID requestId) {
        BomHeader source = findBom(bomId);
        projectAccessService.requireProjectWriteAccess(source.getProjectId());
        revisionLock.lockHeader(source);
        String actor = projectAccessService.requireCurrentUserId();
        // A deterministic primary key is the durable receipt. Soft deletion keeps it reserved forever.
        String createdId = requestId == null ? idGenerator.generate() : revisionId(source.getBomId(), requestId);
        if (requestId != null) {
            var existing = bomHeaderRepository.findById(createdId);
            if (existing.isPresent()) {
                BomHeader result = existing.get();
                if (!actor.equals(result.getCreatedBy()) || !source.getProjectId().equals(result.getProjectId())
                    || !source.getTargetItemId().equals(result.getTargetItemId()))
                    throw new BusinessException(ErrorCode.CONFLICT, "requestId is already used by another revision author.");
                if (!NOT_DELETED.equals(result.getDeletedYn()))
                    throw new BusinessException(ErrorCode.CONFLICT, "The revision created by requestId was deleted; check the revision list.");
                return toResponse(result);
            }
        }
        BomStatus status = BomStatus.fromCode(source.getBomStatus());
        if (status != BomStatus.APPROVED && status != BomStatus.RETIRED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "Only approved or retired revisions can be copied; edit this " + status.code() + " revision instead.");
        }
        int lastVersion = bomHeaderRepository.findTopByProjectIdAndTargetItemIdOrderByBomVersionDesc(
            source.getProjectId(), source.getTargetItemId()).map(BomHeader::getBomVersion).orElse(0);
        if (lastVersion == Integer.MAX_VALUE) throw new BusinessException(ErrorCode.CONFLICT, "bomVersion has reached its limit.");
        int nextVersion = lastVersion + 1;

        BomHeader copy = new BomHeader();
        copy.setBomId(createdId);
        copy.setProjectId(source.getProjectId());
        copy.setTargetItemId(source.getTargetItemId());
        copy.setBomName(source.getBomName());
        copy.setBomVersion(nextVersion);
        copy.setBaseQuantity(source.getBaseQuantity());
        copy.setBaseUnit(source.getBaseUnit());
        copy.setBomStatus(BomStatus.DRAFT.code());
        copy.setApprovalStatus("draft");
        copy.setNote(source.getNote());
        copy.setEffectiveFrom(source.getEffectiveFrom());
        copy.setEffectiveTo(source.getEffectiveTo());
        copy.setCreatedBy(actor);
        copy.setDeletedYn(NOT_DELETED);
        bomHeaderRepository.save(copy);

        for (BomLine sourceLine : lines(source.getBomId())) {
            BomLine line = new BomLine();
            line.setBomLineId(idGenerator.generate());
            line.setBomId(copy.getBomId());
            line.setChildItemId(sourceLine.getChildItemId());
            line.setLineType(sourceLine.getLineType());
            line.setPhantomYn(sourceLine.getPhantomYn());
            line.setQuantity(sourceLine.getQuantity());
            line.setUnit(sourceLine.getUnit());
            line.setScrapRate(sourceLine.getScrapRate());
            line.setOptionalYn(sourceLine.getOptionalYn());
            line.setSubstituteGroup(sourceLine.getSubstituteGroup());
            line.setSortOrder(sourceLine.getSortOrder());
            line.setNote(sourceLine.getNote());
            line.setCreatedBy(actor);
            bomLineRepository.save(line);
        }
        return toResponse(copy);
    }

    @Override
    public BomRequirementResponse calculateRequirements(String bomId, BigDecimal productionQuantity) {
        BomHeader header = findBom(bomId);
        projectAccessService.requireProjectReadAccess(header.getProjectId());
        return requirements(header, productionQuantity, projectCalendar.today(header.getProjectId()), new java.util.HashSet<>());
    }

    @Override
    public BomRequirementResponse requirementsForRun(
        String bomId,
        String projectId,
        String targetItemId,
        BigDecimal productionQuantity
    ) {
        return requirementsForRun(bomId, projectId, targetItemId, productionQuantity, null);
    }

    @Override
    public BomRequirementResponse requirementsForRun(
        String bomId,
        String projectId,
        String targetItemId,
        BigDecimal productionQuantity,
        java.time.LocalDate phantomDay
    ) {
        BomHeader header = bomHeaderRepository.findByBomIdAndDeletedYn(bomId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "BOM does not exist."));
        if (!header.getProjectId().equals(projectId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "The BOM belongs to another project.");
        }
        if (BomStatus.fromCode(header.getBomStatus()) != BomStatus.APPROVED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "BOM " + header.getBomName() + " v" + header.getBomVersion() + " is " + header.getBomStatus()
                    + "; production can only use an approved revision.");
        }
        if (targetItemId != null && !targetItemId.equals(header.getTargetItemId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "The BOM produces a different item than this run.");
        }
        java.time.LocalDate day = phantomDay != null ? phantomDay : projectCalendar.today(projectId);
        return requirements(header, productionQuantity, day, new java.util.HashSet<>());
    }

    /**
     * productionQuantity / base × line quantity, converted to each material's unit. A phantom line is replaced by its item's
     * BOM revision effective on {@code phantomDay}, for the phantom quantity needed; the phantom's own stock is not used
     * (docs/domain/multi-level-bom.md P2-P4). {@code phantoms} holds the phantom items being expanded, against loops.
     */
    private BomRequirementResponse requirements(BomHeader header, BigDecimal productionQuantity, java.time.LocalDate phantomDay,
                                                java.util.Set<String> phantoms) {
        requirePositive(productionQuantity, "Production quantity");
        CatalogItemView target = findProjectItem(header.getTargetItemId(), header.getProjectId());
        BigDecimal base = unitConverter.toItemUnit(header.getBaseQuantity(), header.getBaseUnit(), target.unitId()).quantity();
        requirePositive(base, "Base quantity");
        BigDecimal factor = productionQuantity.divide(base, FACTOR_SCALE, RoundingMode.HALF_UP);

        List<BomRequirementResponse.Line> result = new ArrayList<>();
        List<BomRequirementResponse.Output> outputs = new ArrayList<>();
        BigDecimal materialCost = BigDecimal.ZERO;
        boolean costComplete = true;
        for (BomLine line : lines(header.getBomId())) {
            CatalogItemView child = findProjectItem(line.getChildItemId(), header.getProjectId());
            BigDecimal required = factor.multiply(line.getQuantity()).setScale(8, RoundingMode.HALF_UP).stripTrailingZeros();
            UnitConverter.Conversion conversion = unitConverter.toItemUnit(required, line.getUnit(), child.unitId());
            if (!BomTree.isMaterial(line.getLineType())) {
                // By-products and waste come out of the batch; nothing is consumed or costed for them.
                outputs.add(new BomRequirementResponse.Output(line.getBomLineId(), child.itemId(), line.getLineType(),
                    line.getQuantity(), line.getUnit(), required, conversion.toUnitCode(),
                    conversion.quantity().setScale(STOCK_SCALE, RoundingMode.HALF_UP)));
                continue;
            }
            BigDecimal rate = unitConverter.toItemUnit(BigDecimal.ONE, line.getUnit(), child.unitId()).quantity();
            BigDecimal itemQuantity = conversion.quantity().setScale(STOCK_SCALE, RoundingMode.HALF_UP);
            if ("Y".equals(line.getPhantomYn())) {
                BomRequirementResponse expanded = phantom(header, child, itemQuantity, phantomDay, phantoms);
                for (BomRequirementResponse.Line inner : expanded.lines()) {
                    result.add(new BomRequirementResponse.Line(inner.bomLineId(), inner.childItemId(), inner.lineQuantity(),
                        inner.lineUnit(), inner.requiredQuantity(), inner.itemUnit(), inner.requiredItemQuantity(),
                        inner.conversionRate(), inner.unitCost(), inner.lineCost(),
                        inner.viaItemId() != null ? inner.viaItemId() : child.itemId()));
                }
                outputs.addAll(expanded.outputs());
                materialCost = materialCost.add(expanded.materialCost());
                costComplete = costComplete && expanded.costComplete();
                continue;
            }
            // Unit cost is per the item's own unit, so it multiplies the quantity already converted to that unit.
            BigDecimal unitCost = child.unitCost() != null && child.unitCost().signum() > 0 ? child.unitCost() : null;
            BigDecimal lineCost = unitCost == null ? null : itemQuantity.multiply(unitCost).setScale(STOCK_SCALE, RoundingMode.HALF_UP);
            if (lineCost == null) {
                costComplete = false;
            } else {
                materialCost = materialCost.add(lineCost);
            }
            result.add(new BomRequirementResponse.Line(
                line.getBomLineId(),
                child.itemId(),
                line.getQuantity(),
                line.getUnit(),
                required,
                conversion.toUnitCode(),
                itemQuantity,
                rate,
                unitCost,
                lineCost
            ));
        }
        return new BomRequirementResponse(
            header.getBomId(), header.getBomVersion(), header.getTargetItemId(), productionQuantity, base, result,
            materialCost.setScale(STOCK_SCALE, RoundingMode.HALF_UP), costComplete, outputs);
    }

    /** The phantom item's BOM revision effective on the day, for the quantity of it the line needs (P2-P3). */
    private BomRequirementResponse phantom(BomHeader parent, CatalogItemView item, BigDecimal quantity, java.time.LocalDate day,
                                           java.util.Set<String> phantoms) {
        if (phantoms.contains(item.itemId()) || phantoms.size() >= BomTree.MAX_LEVELS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Phantom " + item.itemCode() + " goes through itself or more than "
                + BomTree.MAX_LEVELS + " levels.");
        }
        BomHeader own = BomTree.approvedByItem(bomHeaderRepository, parent.getProjectId(), day).get(item.itemId());
        if (own == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Phantom " + item.itemCode() + " has no approved BOM effective on "
                + day + ".");
        }
        java.util.Set<String> deeper = new java.util.HashSet<>(phantoms);
        deeper.add(item.itemId());
        return requirements(own, quantity, day, deeper);
    }

    private BomHeader findEditableBom(String bomId) {
        BomHeader header = findBom(bomId);
        projectAccessService.requireProjectWriteAccess(header.getProjectId());
        revisionLock.lockHeader(header);
        BomStatus status = BomStatus.fromCode(header.getBomStatus());
        if (!status.editable()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "Revision " + header.getBomVersion() + " is " + status.code()
                    + " and can no longer change. Create a new revision instead.");
        }
        return header;
    }

    private BomHeader findBom(String bomId) {
        return bomHeaderRepository.findByBomIdAndDeletedYn(bomId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private CatalogItemView findProjectItem(String itemId, String projectId) {
        CatalogItemView item = catalogQuery.findActiveItem(itemId == null ? "" : itemId.trim())
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "Item " + itemId + " does not exist."));
        if (!projectId.equals(item.projectId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Item " + item.itemCode() + " belongs to another project.");
        }
        return item;
    }

    private CatalogItemView lockReferenceItem(String itemId, String projectId) {
        return catalogQuery.lockProjectItemForReference(projectId, itemId == null ? "" : itemId.trim())
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "Item " + itemId + " does not exist."));
    }

    /** Opaque 48-character key, scoped to source and request, within the existing varchar(50) primary key. */
    private static String revisionId(String sourceId, UUID requestId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                ("flowmat:bom:revision:v1:" + sourceId + ":" + requestId).getBytes(StandardCharsets.UTF_8));
            return "bom-rev-" + HexFormat.of().formatHex(digest).substring(0, 40);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by this runtime.", impossible);
        }
    }

    private static void requireActive(CatalogItemView item, String action) {
        if (!ItemStatusRule.isActive(item.itemStatus()))
            throw new BusinessException(ErrorCode.CONFLICT, ItemStatusRule.refusal(item.itemCode(), item.itemStatus(), action));
    }

    private List<BomLine> lines(String bomId) {
        return bomLineRepository.findAllByBomIdOrderBySortOrderAscBomLineIdAsc(bomId);
    }

    BomResponse toResponse(BomHeader header) {
        return toResponse(header, lines(header.getBomId()));
    }

    static BomResponse toResponse(BomHeader header, List<BomLine> lines) {
        return new BomResponse(
            header.getBomId(),
            header.getProjectId(),
            header.getTargetItemId(),
            header.getBomName(),
            header.getBomVersion(),
            header.getBaseQuantity(),
            header.getBaseUnit(),
            header.getBomStatus(),
            header.getApprovedBy(),
            header.getApprovedAt(),
            header.getNote(),
            lines.stream().map(line -> new BomLineResponse(
                line.getBomLineId(),
                line.getChildItemId(),
                line.getQuantity(),
                line.getUnit(),
                line.getScrapRate(),
                line.getOptionalYn(),
                line.getSubstituteGroup(),
                line.getSortOrder(),
                line.getNote(),
                line.getLineType() == null ? BomTree.MATERIAL : line.getLineType(),
                "Y".equals(line.getPhantomYn())
            )).toList(),
            header.getEffectiveFrom(),
            header.getEffectiveTo()
        );
    }

    /** Blank is a material; otherwise material, by_product or waste (docs/domain/bom-by-products.md). */
    static String lineType(String value) {
        if (value == null || value.isBlank()) {
            return BomTree.MATERIAL;
        }
        String type = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!BomTree.LINE_TYPES.contains(type)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "lineType is material, by_product or waste.");
        }
        return type;
    }

    static void requirePositive(BigDecimal value, String label) {
        if (value == null || value.signum() <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, label + " must be greater than 0.");
        }
    }

    private static String trimToNull(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }
}
