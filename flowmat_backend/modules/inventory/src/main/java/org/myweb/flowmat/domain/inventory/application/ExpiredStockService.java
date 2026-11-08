package org.myweb.flowmat.domain.inventory.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.inventory.api.dto.request.ExpiredWriteOffRequest;
import org.myweb.flowmat.domain.inventory.api.dto.response.ExpiredWriteOffResponse;
import org.myweb.flowmat.domain.inventory.domain.entity.Inventory;
import org.myweb.flowmat.domain.inventory.domain.entity.InventoryTransaction;
import org.myweb.flowmat.domain.inventory.domain.entity.LotMaster;
import org.myweb.flowmat.domain.inventory.domain.enums.InventoryTransactionType;
import org.myweb.flowmat.domain.inventory.domain.enums.LotStatus;
import org.myweb.flowmat.domain.inventory.repository.InventoryRepository;
import org.myweb.flowmat.domain.inventory.repository.InventoryTransactionRepository;
import org.myweb.flowmat.domain.inventory.repository.LotMasterRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes off the stock of expired LOTs (docs/domain/lot-expiry.md "만료 재고 폐기"): each stock record's available
 * quantity leaves through an ordinary issue, all in one transaction. Quarantined records stay (an issue is refused until
 * they are released) and reserved stock stays reserved; both are named in the result.
 */
@Service
@RequiredArgsConstructor
public class ExpiredStockService {

    static final String REFERENCE_TYPE = "expiry_write_off";
    private static final String NOT_DELETED = "N";

    private final ProjectAccessService projectAccessService;
    private final org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery projectCalendar;
    private final LotMasterRepository lotMasterRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryTransactionRepository inventoryTransactionRepository;
    private final InventoryCommandService inventoryCommandService;
    private final LotService lotService;
    private final CatalogQuery catalogQuery;

    @Transactional
    public ExpiredWriteOffResponse writeOff(ExpiredWriteOffRequest request) {
        String projectId = request.projectId().trim();
        projectAccessService.requireProjectWriteAccess(projectId);
        if (request.closeLots()) {
            // Checked first, so a missing right does not undo a write-off half-way.
            projectAccessService.requireProjectOwnerAccess(projectId);
        }
        String actor = projectAccessService.requireCurrentUserId();
        String requestId = request.requestId().trim();

        // A retry of a write-off that went through returns what it did.
        List<InventoryTransaction> previous = inventoryTransactionRepository.findAllByReferenceTypeAndReferenceId(REFERENCE_TYPE, requestId)
            .stream()
            .filter(tx -> projectId.equals(tx.getProjectId()))
            .toList();
        if (!previous.isEmpty()) {
            return replay(previous);
        }

        LocalDate today = projectCalendar.today(projectId);
        List<LotMaster> lots = chosenLots(projectId, request.lotIds(), today);
        String note = request.note() == null || request.note().isBlank() ? "Expired stock written off" : request.note().trim();
        List<ExpiredWriteOffResponse.Line> lines = new ArrayList<>();
        for (LotMaster lot : lots) {
            BigDecimal writtenOff = BigDecimal.ZERO;
            List<String> kept = new ArrayList<>();
            for (Inventory row : inventoryRepository.findAllByLotIdAndDeletedYn(lot.getLotId(), NOT_DELETED)) {
                if (row.getQuantity() == null || row.getQuantity().signum() <= 0) {
                    continue;
                }
                if ("quarantined".equalsIgnoreCase(row.getInventoryStatus())) {
                    kept.add(plain(row.getQuantity()) + " quarantined at " + place(row) + "; release it first");
                    continue;
                }
                BigDecimal available = row.getAvailableQuantity() == null ? BigDecimal.ZERO : row.getAvailableQuantity();
                if (available.signum() > 0) {
                    inventoryCommandService.apply(new InventoryMovement(row.getInventoryId(), InventoryTransactionType.ISSUE,
                        available.negate(), BigDecimal.ZERO, REFERENCE_TYPE, requestId, note + " (LOT " + lot.getLotNo() + ")",
                        requestId + ":" + row.getInventoryId(), actor));
                    writtenOff = writtenOff.add(available);
                }
                BigDecimal reserved = row.getReservedQuantity() == null ? BigDecimal.ZERO : row.getReservedQuantity();
                if (reserved.signum() > 0) {
                    kept.add(plain(reserved) + " reserved at " + place(row));
                }
            }
            boolean closed = false;
            if (request.closeLots() && kept.isEmpty() && writtenOff.signum() > 0) {
                lotService.closeLot(lot.getLotId());
                closed = true;
            }
            lines.add(line(lot, writtenOff, closed, kept.isEmpty() ? null : String.join("; ", kept)));
        }
        return summary(lines);
    }

    /** The LOTs asked for, which must have expired, or every expired, open LOT of the project that holds stock. */
    private List<LotMaster> chosenLots(String projectId, List<String> lotIds, LocalDate today) {
        List<LotMaster> all = lotMasterRepository.findAllByProjectIdOrderByCreatedAtDesc(projectId);
        if (lotIds == null || lotIds.isEmpty()) {
            return all.stream()
                .filter(lot -> lot.isExpiredOn(today) && LotStatus.fromCode(lot.getLotStatus()) != LotStatus.CLOSED)
                .filter(lot -> inventoryRepository.findAllByLotIdAndDeletedYn(lot.getLotId(), NOT_DELETED).stream()
                    .anyMatch(row -> row.getQuantity() != null && row.getQuantity().signum() > 0))
                .sorted(Comparator.comparing(LotMaster::getExpiryDate).thenComparing(LotMaster::getLotNo))
                .toList();
        }
        Map<String, LotMaster> byId = all.stream().collect(Collectors.toMap(LotMaster::getLotId, Function.identity()));
        List<LotMaster> chosen = new ArrayList<>();
        for (String id : new LinkedHashSet<>(lotIds)) {
            LotMaster lot = byId.get(id);
            if (lot == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "LOT " + id + " is not in this project.");
            }
            if (!lot.isExpiredOn(today)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "LOT " + lot.getLotNo() + " has not expired; issue it the usual way.");
            }
            if (LotStatus.fromCode(lot.getLotStatus()) == LotStatus.CLOSED) {
                throw new BusinessException(ErrorCode.CONFLICT, "LOT " + lot.getLotNo() + " is closed.");
            }
            chosen.add(lot);
        }
        return chosen;
    }

    private ExpiredWriteOffResponse replay(List<InventoryTransaction> recorded) {
        Map<String, BigDecimal> byLot = new LinkedHashMap<>();
        for (InventoryTransaction tx : recorded) {
            byLot.merge(tx.getLotId(), tx.getQuantityDelta().negate(), BigDecimal::add);
        }
        List<ExpiredWriteOffResponse.Line> lines = new ArrayList<>();
        byLot.forEach((lotId, quantity) -> lotMasterRepository.findById(lotId).ifPresent(lot ->
            lines.add(line(lot, quantity, LotStatus.fromCode(lot.getLotStatus()) == LotStatus.CLOSED, null))));
        return summary(lines);
    }

    private ExpiredWriteOffResponse.Line line(LotMaster lot, BigDecimal writtenOff, boolean closed, String note) {
        Optional<CatalogItemView> item = Optional.ofNullable(catalogQuery.findItems(List.of(lot.getItemId())).get(lot.getItemId()));
        String unit = item.map(CatalogItemView::unitCode).orElse(null);
        BigDecimal cost = item.map(CatalogItemView::unitCost).filter(c -> c.signum() > 0).orElse(null);
        return new ExpiredWriteOffResponse.Line(lot.getLotId(), lot.getLotNo(), lot.getItemId(), item.map(CatalogItemView::itemCode).orElse(null),
            unit, writtenOff, cost == null ? null : writtenOff.multiply(cost), closed, note);
    }

    private static ExpiredWriteOffResponse summary(List<ExpiredWriteOffResponse.Line> lines) {
        BigDecimal value = lines.stream().map(ExpiredWriteOffResponse.Line::value).filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean complete = lines.stream().noneMatch(line -> line.value() == null && line.writtenOff().signum() > 0);
        return new ExpiredWriteOffResponse(lines.size(), value, complete, lines);
    }

    private static String place(Inventory row) {
        return row.getLocation() == null ? "no location" : row.getLocation();
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
