package org.myweb.flowmat.domain.inventory.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.api.dto.request.AllocatedStockTransferRequest;
import org.myweb.flowmat.domain.inventory.api.dto.request.InventoryTransferRequest;
import org.myweb.flowmat.domain.inventory.api.dto.response.InventoryTransferResponse;
import org.myweb.flowmat.domain.inventory.domain.entity.InventoryTransaction;
import org.myweb.flowmat.domain.inventory.domain.enums.LotStatus;
import org.myweb.flowmat.domain.inventory.repository.InventoryRepository;
import org.myweb.flowmat.domain.inventory.repository.InventoryTransactionRepository;
import org.myweb.flowmat.domain.inventory.repository.LotMasterRepository;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.myweb.flowmat.domain.inventory.application.publicapi.WorkOrderAllocations;

/** Inventory orchestrates the stock movement and production allocation change in one transaction. */
@Service
@RequiredArgsConstructor
public class AllocatedStockTransferService {
    private final WorkOrderAllocations allocations;
    private final org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery projectCalendar;
    private final InventoryRepository stocks;
    private final InventoryTransactionRepository transactions;
    private final LotMasterRepository lots;
    private final InventoryTransferService transfers;
    private final StorageLocationService locations;
    private final ProjectAccess access;
    @Transactional
    public InventoryTransferResponse transfer(AllocatedStockTransferRequest request) {
        String project = text(request.projectId()); String order = text(request.workOrderId());
        String allocation = text(request.allocationId()); String source = text(request.fromInventoryId());
        String destination = text(request.toLocation());
        access.requireProjectWriteAccess(project);
        String key = "allocated-pick:" + request.requestId();
        String note = "Allocated pick|" + order + "|" + allocation + "|" + source + "|" + destination.toLowerCase(Locale.ROOT);
        var previous = transactions.findByProjectIdAndRequestId(project, key).orElse(null);
        if (previous != null) return replay(previous, request.quantity(), source, note);
        // Match pick-list planning: destination code lock, then production locks, then stock rows.
        locations.resolveForStock(project, destination);
        // Match the allocation/release lock order before touching stock rows.
        var locked = allocations.lock(project, order, allocation);
        previous = transactions.findByProjectIdAndRequestId(project, key).orElse(null);
        if (previous != null) return replay(previous, request.quantity(), source, note);
        if (!locked.movable() || !source.equals(locked.inventoryId()) || request.quantity().compareTo(locked.remaining()) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "Only this work order's remaining allocated stock can be picked.");
        }
        // The transfer locks both stock rows in id order; locking only the source here could deadlock an opposite transfer.
        var stock = stocks.findByInventoryIdAndDeletedYn(source, "N").filter(found -> project.equals(found.getProjectId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (stock.getLotId() != null) {
            var lot = lots.findById(stock.getLotId()).orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "The allocated LOT is missing."));
            if (!LotStatus.fromCode(lot.getLotStatus()).usable() || lot.isExpiredOn(projectCalendar.today(project))) {
                throw new BusinessException(ErrorCode.CONFLICT, "An unavailable or expired LOT cannot be picked for production.");
            }
        }
        var moved = transfers.transferReserved(new InventoryTransferRequest(source, destination, request.quantity(), key, note));
        allocations.move(project, order, allocation, source, moved.in().inventoryId(), request.quantity());
        return moved;
    }
    private InventoryTransferResponse replay(InventoryTransaction previous, BigDecimal quantity, String source, String note) {
        if (!"transfer_out".equals(previous.getTransactionType()) || !source.equals(previous.getInventoryId())
            || previous.getQuantityDelta().negate().compareTo(quantity) != 0
            || previous.getReservedDelta() == null || previous.getReservedDelta().negate().compareTo(quantity) != 0
            || !"inventory_transfer".equals(previous.getReferenceType()) || !note.equals(previous.getNote())
            || !Objects.equals(access.requireCurrentUserId(), previous.getCreatedBy())) {
            throw new BusinessException(ErrorCode.CONFLICT, "requestId belongs to a different allocated stock move.");
        }
        return transfers.response(previous.getReferenceId());
    }
    private static String text(String value) {
        if (value == null || value.isBlank() || value.indexOf(0) >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Stock move fields must contain storable text.");
        }
        return value.trim();
    }
}
