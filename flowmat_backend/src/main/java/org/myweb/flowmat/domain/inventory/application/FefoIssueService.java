package org.myweb.flowmat.domain.inventory.application;

import java.math.BigDecimal;
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
import org.myweb.flowmat.domain.inventory.api.dto.request.FefoIssueRequest;
import org.myweb.flowmat.domain.inventory.api.dto.response.FefoIssueResponse;
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
 * Issues or reserves a quantity of a LOT-tracked item from its LOTs, first-expiring first (docs/domain/lot-expiry.md "재고
 * 출고 나눠 하기"): one ordinary issue or reservation per stock record through {@link InventoryCommandService}, all in one
 * transaction. Expired, closed and quarantined stock is left alone; issuing it to scrap it stays a per-record movement.
 * A reservation is undone like any movement, by reversing its lines in the ledger.
 */
@Service
@RequiredArgsConstructor
public class FefoIssueService {

    static final String REFERENCE_TYPE = "fefo_issue";
    static final String RESERVE_REFERENCE_TYPE = "fefo_reserve";
    private static final String NOT_DELETED = "N";

    private final ProjectAccessService projectAccessService;
    private final org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery projectCalendar;
    private final InventoryCatalogReferences catalogReferences;
    private final UnitConverter unitConverter;
    private final InventoryRepository inventoryRepository;
    private final InventoryTransactionRepository inventoryTransactionRepository;
    private final LotMasterRepository lotMasterRepository;
    private final InventoryCommandService inventoryCommandService;

    @Transactional
    public FefoIssueResponse issue(FefoIssueRequest request) {
        String projectId = request.projectId().trim();
        projectAccessService.requireProjectWriteAccess(projectId);
        String actor = projectAccessService.requireCurrentUserId();
        CatalogItemView item = catalogReferences.item(request.itemId())
            .filter(found -> projectId.equals(found.projectId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!"Y".equals(item.lotManageYn())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, item.itemCode() + " is not LOT-tracked; issue from its stock record.");
        }
        String unit = item.unitId() == null ? null : catalogReferences.unitCode(item.unitId()).orElse(null);
        String requestId = request.requestId().trim();
        boolean reserve = "reserve".equalsIgnoreCase(request.action() == null ? "" : request.action().trim());
        String referenceType = reserve ? RESERVE_REFERENCE_TYPE : REFERENCE_TYPE;
        String verb = reserve ? "reserve" : "issue";

        // A retry of a request that went through returns what it did. The key cannot be reused for the other action,
        // whose per-record keys it would collide with.
        if (inventoryTransactionRepository.findAllByReferenceTypeAndReferenceId(reserve ? REFERENCE_TYPE : RESERVE_REFERENCE_TYPE, requestId)
            .stream()
            .anyMatch(tx -> projectId.equals(tx.getProjectId()))) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "requestId '" + requestId + "' was already used to " + (reserve ? "issue" : "reserve") + " stock.");
        }
        List<InventoryTransaction> previous = inventoryTransactionRepository.findAllByReferenceTypeAndReferenceId(referenceType, requestId)
            .stream()
            .filter(tx -> projectId.equals(tx.getProjectId()))
            .toList();
        if (!previous.isEmpty()) {
            if (previous.stream().anyMatch(tx -> !item.itemId().equals(tx.getItemId()))) {
                throw new BusinessException(ErrorCode.CONFLICT, "requestId '" + requestId + "' was already used to " + verb + " a different item.");
            }
            return response(item, unit, reserve, previous);
        }

        BigDecimal needed = request.unit() == null || request.unit().isBlank()
            ? request.quantity()
            : unitConverter.toItemUnit(request.quantity(), request.unit(), item.unitId()).quantity();
        // Locked before they are read, so a concurrent movement waits instead of draining a record this split counts on.
        List<Inventory> stock = inventoryRepository.findAllById(inventoryRepository.lockItemStock(projectId, item.itemId())).stream()
            .sorted(Comparator.comparing(Inventory::getCreatedAt, Comparator.nullsLast(Comparator.<OffsetDateTime>naturalOrder()))
                .thenComparing(Inventory::getInventoryId))
            .filter(row -> row.getLotId() != null)
            .filter(row -> !"quarantined".equalsIgnoreCase(row.getInventoryStatus()))
            .filter(row -> row.getAvailableQuantity() != null && row.getAvailableQuantity().signum() > 0)
            .toList();
        Map<String, LotMaster> lots = lotMasterRepository.findAllById(stock.stream().map(Inventory::getLotId).distinct().toList())
            .stream()
            .collect(Collectors.toMap(LotMaster::getLotId, Function.identity()));
        LocalDate today = projectCalendar.today(projectId);
        List<Inventory> usable = stock.stream()
            .filter(row -> {
                LotMaster lot = lots.get(row.getLotId());
                return lot != null && LotStatus.fromCode(lot.getLotStatus()).usable() && !lot.isExpiredOn(today);
            })
            .sorted(Comparator
                .comparing((Inventory row) -> lots.get(row.getLotId()).getExpiryDate(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(row -> lots.get(row.getLotId()).getReceivedAt(), Comparator.nullsLast(Comparator.<OffsetDateTime>naturalOrder()))
                .thenComparing(row -> lots.get(row.getLotId()).getLotNo(), Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
        BigDecimal available = usable.stream().map(Inventory::getAvailableQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (available.compareTo(needed) < 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "Only " + plain(available) + (unit == null ? "" : " " + unit) + " of "
                + item.itemCode() + " is available in LOTs that have not expired; " + plain(needed) + " needed. Nothing was "
                + (reserve ? "reserved." : "issued."));
        }

        String note = request.note() == null || request.note().isBlank()
            ? (reserve ? "Reserved" : "Issued") + " first-expiring first"
            : request.note().trim();
        List<InventoryTransaction> issued = new ArrayList<>();
        BigDecimal remaining = needed;
        for (Inventory row : usable) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal piece = row.getAvailableQuantity().min(remaining);
            issued.add(inventoryCommandService.apply(reserve
                ? new InventoryMovement(row.getInventoryId(), InventoryTransactionType.RESERVE, BigDecimal.ZERO, piece,
                    referenceType, requestId, note, requestId + ":" + row.getInventoryId(), actor)
                : new InventoryMovement(row.getInventoryId(), InventoryTransactionType.ISSUE, piece.negate(), BigDecimal.ZERO,
                    referenceType, requestId, note, requestId + ":" + row.getInventoryId(), actor)));
            remaining = remaining.subtract(piece);
        }
        return response(item, unit, reserve, issued);
    }

    private FefoIssueResponse response(CatalogItemView item, String unit, boolean reserve, List<InventoryTransaction> transactions) {
        Map<String, LotMaster> lots = lotMasterRepository.findAllById(transactions.stream()
                .map(InventoryTransaction::getLotId).filter(id -> id != null).distinct().toList())
            .stream()
            .collect(Collectors.toMap(LotMaster::getLotId, Function.identity()));
        Map<String, Inventory> records = inventoryRepository.findAllById(transactions.stream()
                .map(InventoryTransaction::getInventoryId).distinct().toList())
            .stream()
            .collect(Collectors.toMap(Inventory::getInventoryId, Function.identity()));
        List<FefoIssueResponse.Line> lines = transactions.stream()
            .map(tx -> new FefoIssueResponse.Line(tx.getInventoryTransactionId(), tx.getInventoryId(), tx.getLotId(),
                tx.getLotId() == null || !lots.containsKey(tx.getLotId()) ? null : lots.get(tx.getLotId()).getLotNo(),
                records.containsKey(tx.getInventoryId()) ? records.get(tx.getInventoryId()).getLocation() : null,
                reserve ? tx.getReservedDelta() : tx.getQuantityDelta().negate(), tx.getQuantityAfter(), tx.getReservedAfter()))
            .toList();
        BigDecimal total = lines.stream().map(FefoIssueResponse.Line::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new FefoIssueResponse(item.itemId(), reserve ? "reserve" : "issue", total, unit, lines);
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
