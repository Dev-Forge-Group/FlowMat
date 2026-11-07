package org.myweb.flowmat.domain.production.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.UnitConverter;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockView;
import org.myweb.flowmat.domain.inventory.application.publicapi.LotView;
import org.myweb.flowmat.domain.production.api.dto.request.ProductionRunItemRecordRequest;
import org.myweb.flowmat.domain.production.api.dto.request.RunInputAllocationRequest;
import org.myweb.flowmat.domain.production.api.dto.response.ProductionRunItemResponse;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.domain.production.repository.StockAllocationRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Takes one input from a LOT-tracked item's stock, first-expiring LOT first (docs/domain/lot-expiry.md "여러 LOT에 나눠
 * 투입"): one ordinary recording per LOT, all in one transaction, so either the whole quantity is recorded or nothing is.
 * Each recording goes through {@link ProductionRunService#recordRunItem}, so its checks, stock movement and LOT genealogy
 * are the usual ones.
 */
@Service
@RequiredArgsConstructor
public class RunInputAllocationService {

    private static final String NOT_DELETED = "N";

    private final ProductionRunService productionRunService;
    private final ProductionPlanningReferences references;
    private final org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery projectCalendar;
    private final ProductionRunRepository productionRunRepository;
    private final ProjectAccessService projectAccessService;
    private final UnitConverter unitConverter;
    private final StockAllocationRepository stockAllocationRepository;
    private final StockAllocationService stockAllocationService;

    @Transactional
    public List<ProductionRunItemResponse> allocate(String productionRunId, RunInputAllocationRequest request) {
        // Match recording lock order: run, order, project allocations, then stock.
        ProductionRun run = productionRunRepository.findForUpdate(productionRunId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        projectAccessService.requireProjectWriteAccess(run.getProjectId());
        ProductionRunServiceImpl.requireOpenRun(run);
        ProductionText.requireStorable(request.itemId(), "itemId");
        ProductionText.requireStorable(request.processId(), "processId");
        CatalogItemView item = references.item(request.itemId())
            .filter(found -> run.getProjectId().equals(found.projectId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!"Y".equals(item.lotManageYn())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, item.itemCode() + " is not LOT-tracked; record it directly.");
        }
        // Allocate in the item's own unit; each piece is then recorded in that unit.
        String unit = ProductionRunServiceImpl.storedUnit(request.unit());
        UnitConverter.Conversion conversion = unitConverter.toItemUnit(request.quantity(), unit, item.unitId());
        if (conversion.quantity().compareTo(new BigDecimal("0.00005")) < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "quantity must round to at least 0.0001 in the item's stock unit.");
        }
        BigDecimal needed = conversion.quantity().setScale(4, RoundingMode.HALF_UP);
        String recordUnit = item.unitId() == null
            ? unit
            : references.unitCode(item.unitId()).orElse(unit);

        // An actual run can consume its order's reservation through the ordinary recording path.
        // Simulations do not release physical reservations, so they can only plan against free stock.
        Map<String, BigDecimal> ownAllocated = ownAllocations(run);

        // Locked before they are read, so a concurrent movement waits instead of draining a record this split counts on.
        List<StockView> stock = references.lockItemStock(run.getProjectId(), item.itemId())
            .stream()
            .sorted(Comparator.comparing(StockView::createdAt, Comparator.nullsLast(Comparator.<OffsetDateTime>naturalOrder()))
                .thenComparing(StockView::inventoryId))
            .filter(row -> row.lotId() != null)
            .filter(row -> !"quarantined".equalsIgnoreCase(row.inventoryStatus()))
            .filter(row -> usableQuantity(row, ownAllocated).signum() > 0)
            .toList();
        Map<String, LotView> lots = references.lots(stock.stream().map(StockView::lotId).distinct().toList())
            .stream()
            .collect(Collectors.toMap(LotView::lotId, Function.identity()));
        LocalDate today = projectCalendar.today(run.getProjectId());
        List<StockView> usable = stock.stream()
            .filter(row -> {
                LotView lot = lots.get(row.lotId());
                return lot != null && lot.usable() && !lot.isExpiredOn(today);
            })
            .sorted(Comparator
                .comparing((StockView row) -> lots.get(row.lotId()).expiryDate(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(row -> lots.get(row.lotId()).receivedAt(), Comparator.nullsLast(Comparator.<OffsetDateTime>naturalOrder()))
                .thenComparing(row -> lots.get(row.lotId()).lotNo(), Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
        BigDecimal available = usable.stream().map(row -> usableQuantity(row, ownAllocated)).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (available.compareTo(needed) < 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "Only " + available.stripTrailingZeros().toPlainString() + " " + recordUnit
                + " of " + item.itemCode() + " is in usable LOTs; " + needed.stripTrailingZeros().toPlainString()
                + " needed. Nothing was recorded.");
        }

        List<ProductionRunItemResponse> recorded = new ArrayList<>();
        BigDecimal remaining = needed;
        for (StockView row : usable) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal piece = usableQuantity(row, ownAllocated).min(remaining);
            recorded.add(productionRunService.recordRunItem(productionRunId, new ProductionRunItemRecordRequest(
                request.processId(), null, row.inventoryId(), item.itemId(), "input", piece, piece, recordUnit)));
            remaining = remaining.subtract(piece);
        }
        return recorded;
    }

    private Map<String, BigDecimal> ownAllocations(ProductionRun run) {
        if (!run.affectsPhysicalState() || run.getWorkOrderId() == null) {
            return Map.of();
        }
        stockAllocationService.lockForConsumption(run.getWorkOrderId());
        return stockAllocationRepository.findAllByWorkOrderIdAndStatusOrderByCreatedAtAscAllocationIdAsc(run.getWorkOrderId(), "open")
            .stream().collect(Collectors.toMap(allocation -> allocation.getInventoryId(), allocation -> allocation.remaining(), BigDecimal::add));
    }

    private static BigDecimal usableQuantity(StockView row, Map<String, BigDecimal> ownAllocated) {
        return row.availableQuantity() == null ? BigDecimal.ZERO
            : row.availableQuantity().add(ownAllocated.getOrDefault(row.inventoryId(), BigDecimal.ZERO));
    }
}
