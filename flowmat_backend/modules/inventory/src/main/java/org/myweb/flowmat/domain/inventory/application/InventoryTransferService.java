package org.myweb.flowmat.domain.inventory.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.api.dto.request.InventoryTransferRequest;
import org.myweb.flowmat.domain.inventory.api.dto.response.InventoryTransferResponse;
import org.myweb.flowmat.domain.inventory.domain.entity.Inventory;
import org.myweb.flowmat.domain.inventory.domain.entity.InventoryTransaction;
import org.myweb.flowmat.domain.inventory.domain.enums.InventoryTransactionType;
import org.myweb.flowmat.domain.inventory.repository.InventoryRepository;
import org.myweb.flowmat.domain.inventory.repository.InventoryTransactionRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/**
 * Moving stock to another place (docs/domain/stock-transfer.md): a transfer_out from the source record and a transfer_in
 * to the record of the same item and LOT at the destination, created empty when there is none, in one transaction.
 * Both legs go through {@link InventoryCommandService}, so quarantine, reservations, closed LOTs and stock alerts behave
 * as for any movement.
 */
@Service
@RequiredArgsConstructor
public class InventoryTransferService {

    static final String REFERENCE_TYPE = "inventory_transfer";
    private static final String NOT_DELETED = "N";
    private static final String QUARANTINED = "quarantined";

    private final InventoryRepository inventoryRepository;
    private final InventoryTransactionRepository inventoryTransactionRepository;
    private final InventoryCommandService inventoryCommandService;
    private final ProjectAccessService projectAccessService;
    private final IdGenerator idGenerator;
    private final StorageLocationService storageLocationService;

    @Transactional
    public InventoryTransferResponse transfer(InventoryTransferRequest request) {
        return transfer(request, false);
    }

    /** Reserved movement for the allocation orchestrator; it must hold the production allocation lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    InventoryTransferResponse transferReserved(InventoryTransferRequest request) {
        return transfer(request, true);
    }

    private InventoryTransferResponse transfer(InventoryTransferRequest request, boolean reserved) {
        Inventory from = inventoryRepository.findByInventoryIdAndDeletedYn(request.fromInventoryId(), NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        projectAccessService.requireProjectWriteAccess(from.getProjectId());
        String actor = projectAccessService.requireCurrentUserId();
        BigDecimal quantity = request.quantity();
        if (quantity == null || quantity.signum() <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Move more than 0.");
        }
        String requestId = request.requestId().trim();

        InventoryTransaction previous = inventoryTransactionRepository
            .findByProjectIdAndRequestId(from.getProjectId(), requestId).orElse(null);
        if (previous != null) {
            if (InventoryTransactionType.TRANSFER_OUT.code().equals(previous.getTransactionType())
                && from.getInventoryId().equals(previous.getInventoryId())
                && previous.getQuantityDelta().negate().compareTo(quantity) == 0) {
                return response(previous.getReferenceId());
            }
            throw new BusinessException(ErrorCode.CONFLICT,
                "requestId '" + requestId + "' was already used for a different stock movement.");
        }

        String toLocation = trimToNull(request.toLocation());
        if (toLocation != null && toLocation.equalsIgnoreCase(Objects.toString(trimToNull(from.getLocation()), ""))) {
            toLocation = trimToNull(from.getLocation());
        } else if (toLocation != null) {
            // A project with a location list moves stock only to an active listed place (docs/domain/storage-location.md).
            toLocation = storageLocationService.resolveForStock(from.getProjectId(), toLocation);
        }
        if (Objects.equals(toLocation, trimToNull(from.getLocation()))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "The stock is already " + (toLocation == null ? "without a location" : "at " + toLocation) + "; pick another place.");
        }
        Inventory to = destination(from, toLocation);
        if (QUARANTINED.equals(to.getInventoryStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "The stock at " + describe(toLocation)
                + " is quarantined. Release it or move to another place.");
        }

        String transferId = idGenerator.generate();
        String note = trimToNull(request.note());
        InventoryMovement out = new InventoryMovement(from.getInventoryId(), InventoryTransactionType.TRANSFER_OUT, quantity.negate(),
            reserved ? quantity.negate() : BigDecimal.ZERO, REFERENCE_TYPE, transferId, note != null ? note : "Moved to " + describe(toLocation), requestId, actor);
        InventoryMovement in = new InventoryMovement(to.getInventoryId(), InventoryTransactionType.TRANSFER_IN, quantity,
            reserved ? quantity : BigDecimal.ZERO, REFERENCE_TYPE, transferId, note != null ? note : "Moved from " + describe(trimToNull(from.getLocation())),
            null, actor);
        // Lock the two rows in a fixed order so two opposite transfers cannot deadlock.
        if (from.getInventoryId().compareTo(to.getInventoryId()) < 0) {
            inventoryCommandService.apply(out);
            inventoryCommandService.apply(in);
        } else {
            inventoryCommandService.apply(in);
            inventoryCommandService.apply(out);
        }
        return response(transferId);
    }

    /**
     * The record of the same item and LOT at the destination; an empty one is created when there is none. Two transfers
     * into the same new place take turns, so neither trips the one-record-per-LOT-and-place index and a non-LOT item does
     * not get two records there.
     */
    private Inventory destination(Inventory from, String toLocation) {
        inventoryRepository.lockStockPlace(from.getProjectId(), from.getItemId(), from.getLotId(), toLocation);
        if (from.getLotId() != null) {
            return inventoryRepository.findLotStockAt(from.getProjectId(), from.getItemId(), from.getLotId(), toLocation)
                .orElseGet(() -> createEmpty(from, toLocation));
        }
        List<Inventory> existing = inventoryRepository.findStockAt(from.getProjectId(), from.getItemId(), toLocation);
        return existing.stream()
            .filter(row -> !QUARANTINED.equals(row.getInventoryStatus()))
            .findFirst()
            .orElseGet(() -> existing.isEmpty() ? createEmpty(from, toLocation) : existing.get(0));
    }

    private Inventory createEmpty(Inventory from, String toLocation) {
        Inventory row = new Inventory();
        row.setInventoryId(idGenerator.generate());
        row.setProjectId(from.getProjectId());
        row.setItemId(from.getItemId());
        row.setLotId(from.getLotId());
        row.setLocation(toLocation);
        row.setQuantity(BigDecimal.ZERO);
        row.setReservedQuantity(BigDecimal.ZERO);
        row.setAvailableQuantity(BigDecimal.ZERO);
        row.setInventoryStatus("available");
        row.setMinThreshold(BigDecimal.ZERO);
        row.setDeletedYn(NOT_DELETED);
        return inventoryRepository.saveAndFlush(row);
    }

    InventoryTransferResponse response(String transferId) {
        List<InventoryTransaction> legs = inventoryTransactionRepository.findAllByReferenceTypeAndReferenceId(REFERENCE_TYPE, transferId);
        return new InventoryTransferResponse(
            transferId,
            legs.stream().filter(leg -> InventoryTransactionType.TRANSFER_OUT.code().equals(leg.getTransactionType()))
                .findFirst().map(InventoryTransactionServiceImpl::toResponse).orElse(null),
            legs.stream().filter(leg -> InventoryTransactionType.TRANSFER_IN.code().equals(leg.getTransactionType()))
                .findFirst().map(InventoryTransactionServiceImpl::toResponse).orElse(null)
        );
    }

    private static String describe(String location) {
        return location == null ? "no location" : location;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
