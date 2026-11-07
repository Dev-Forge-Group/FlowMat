package org.myweb.flowmat.domain.inventory.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.myweb.flowmat.domain.inventory.domain.entity.Inventory;
import org.myweb.flowmat.domain.inventory.domain.entity.InventoryTransaction;
import org.myweb.flowmat.domain.inventory.domain.entity.LotMaster;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockMovementCommand;
import org.myweb.flowmat.domain.inventory.domain.enums.InventoryTransactionType;
import org.myweb.flowmat.domain.inventory.repository.InventoryRepository;
import org.myweb.flowmat.domain.inventory.repository.InventoryTransactionRepository;
import org.myweb.flowmat.domain.inventory.repository.LotMasterRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The only place stock quantities change (docs/domain/inventory-bom-lot-contract.md §1–3).
 *
 * <p>Each call runs in the caller's transaction: the conditional UPDATE, the history row and any LOT status change
 * commit or roll back together. Access checks are the caller's job.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryCommandService implements StockMovementCommand {

    static final String QUARANTINED = "quarantined";
    /** A LOT waiting for its receipt checks (docs/domain/lot-release.md); its rows are quarantined until quality releases it. */
    static final String INSPECTION_PENDING = "inspection_pending";
    /** Reference of the movements that release a LOT after its receipt checks. */
    static final String INSPECTION_RELEASE = "lot_inspection_release";
    /** Reference of the movements that hold a reopened LOT's records for its receipt checks again. */
    static final String LOT_REOPEN = "lot_reopen";
    private static final String AVAILABLE = "available";
    private static final String NOT_DELETED = "N";

    private final EntityManager entities;
    private final InventoryRepository inventoryRepository;
    private final org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery projectCalendar;
    private final InventoryTransactionRepository inventoryTransactionRepository;
    private final LotMasterRepository lotMasterRepository;
    private final IdGenerator idGenerator;
    private final LotStatusResync lotStatusResync;
    private final StockAlertService stockAlertService;
    private final StorageConditionGuard storageConditionGuard;

    /** Another context's movement: the inventory transaction type of the same name. */
    @Override
    @Transactional
    public void apply(StockMovement movement) {
        apply(new InventoryMovement(movement.inventoryId(), InventoryTransactionType.valueOf(movement.kind().name()),
            movement.quantityDelta(), movement.reservedDelta(), movement.referenceType(), movement.referenceId(), movement.note(),
            movement.requestId(), movement.actorUserId()));
    }

    /** Applies the movement if it keeps every stock invariant, and records it. */
    @Transactional
    public InventoryTransaction apply(InventoryMovement movement) {
        Inventory before = findActiveInventory(movement.inventoryId());
        InventoryTransactionType type = movement.type();
        OffsetDateTime now = OffsetDateTime.now();
        BigDecimal quantityDelta = zeroIfNull(movement.quantityDelta());
        BigDecimal reservedDelta = zeroIfNull(movement.reservedDelta());

        if (before.getLotId() != null) {
            LotMaster lot = lotMasterRepository.findById(before.getLotId()).orElse(null);
            if (lot != null) {
                if (type.changesStatusOnly()) lockLot(lot);
                else lockLotForStock(lot);
            }
            if (lot != null && "closed".equals(lot.getLotStatus())) {
                throw new BusinessException(ErrorCode.CONFLICT, "LOT " + lot.getLotNo() + " is closed.");
            }
            // Expired stock can still be issued or adjusted away, but not used in production or held for it.
            if (lot != null && (type == InventoryTransactionType.PRODUCTION_INPUT || type == InventoryTransactionType.RESERVE)
                && lot.isExpiredOn(projectCalendar.today(before.getProjectId()))) {
                throw new BusinessException(ErrorCode.CONFLICT, "LOT " + lot.getLotNo() + " expired on " + lot.getExpiryDate()
                    + "; it cannot be used or reserved. Issue it to scrap it.");
            }
        }

        if (type.changesStatusOnly()) {
            // Preserve this transaction's pending edits before checking the current state under its row lock.
            entities.flush();
            entities.refresh(before, LockModeType.PESSIMISTIC_WRITE);
            changeQuarantine(before, type == InventoryTransactionType.QUARANTINE, now);
        } else {
            if (quantityDelta.signum() == 0 && reservedDelta.signum() == 0) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "A stock movement must change the quantity.");
            }
            int changed = inventoryRepository.applyMovement(
                before.getInventoryId(), quantityDelta, reservedDelta, !type.blockedByQuarantine(), now);
            if (changed == 0) {
                throw rejection(before.getInventoryId(), type, quantityDelta, reservedDelta);
            }
        }

        // The UPDATE cleared the persistence context and holds the row lock until commit, so this read is exact.
        Inventory after = findActiveInventory(before.getInventoryId());
        if (after.getLotId() != null) {
            syncLotStatus(after.getLotId());
        }
        return record(after, type, quantityDelta, reservedDelta, movement.referenceType(), movement.referenceId(),
            movement.note(), movement.requestId(), movement.actorUserId());
    }

    /**
     * Records a movement whose row change the caller already made (creating a stock record, or an absolute adjustment
     * through PUT /inventories/{id}); {@code inventory} must hold the values after the change.
     */
    @Transactional
    public InventoryTransaction record(
        Inventory inventory,
        InventoryTransactionType type,
        BigDecimal quantityDelta,
        BigDecimal reservedDelta,
        String referenceType,
        String referenceId,
        String note,
        String requestId,
        String actorUserId
    ) {
        BigDecimal quantity = zeroIfNull(quantityDelta);
        BigDecimal reserved = zeroIfNull(reservedDelta);
        // Stock coming into a place: a project may refuse a place kept otherwise than the item (storage-location.md SL5).
        if (quantity.signum() > 0 && (type == InventoryTransactionType.RECEIPT || type == InventoryTransactionType.TRANSFER_IN
            || type == InventoryTransactionType.PRODUCTION_OUTPUT)) {
            storageConditionGuard.check(inventory.getProjectId(), inventory.getItemId(), inventory.getLocation());
        }
        InventoryTransaction transaction = new InventoryTransaction();
        transaction.setInventoryTransactionId(idGenerator.generate());
        transaction.setInventoryId(inventory.getInventoryId());
        transaction.setProjectId(inventory.getProjectId());
        transaction.setItemId(inventory.getItemId());
        transaction.setLotId(inventory.getLotId());
        transaction.setTransactionType(type.code());
        transaction.setQuantityDelta(quantity);
        transaction.setReservedDelta(reserved);
        transaction.setAvailableDelta(quantity.subtract(reserved));
        transaction.setQuantityAfter(inventory.getQuantity());
        transaction.setReservedAfter(inventory.getReservedQuantity());
        transaction.setAvailableAfter(inventory.getAvailableQuantity());
        transaction.setReferenceType(trimToNull(referenceType));
        transaction.setReferenceId(trimToNull(referenceId));
        transaction.setNote(trimToNull(note));
        transaction.setRequestId(trimToNull(requestId));
        transaction.setCreatedBy(actorUserId);
        // Flush now so a duplicate requestId or second reversal fails here, inside the caller's transaction.
        InventoryTransaction saved = inventoryTransactionRepository.saveAndFlush(transaction);
        // The movement still holds the row lock, so the alert check sees numbers nobody else can change meanwhile.
        stockAlertService.evaluate(inventory);
        return saved;
    }

    /**
     * Re-checks a row's stock alerts after a change that is not a movement: new thresholds, or the row being deleted.
     * The caller has just written the row, so it holds the row lock.
     */
    @Transactional
    public void refreshAlerts(Inventory inventory) {
        stockAlertService.evaluate(inventory);
    }

    /**
     * Reverses every not-yet-reversed movement recorded for a source record (e.g. one production run item), checking
     * today's stock like any other movement. Used when that source is cancelled; the external reversal endpoint refuses
     * production movements so the run and the stock cannot drift apart.
     */
    @Override
    @Transactional
    public int reverseMovementsOf(String referenceType, String referenceId, String reason, String actorUserId) {
        int reversed = 0;
        for (InventoryTransaction original : inventoryTransactionRepository.findAllByReferenceTypeAndReferenceId(referenceType, referenceId)) {
            boolean alreadyReversed = inventoryTransactionRepository
                .findByReferenceIdAndTransactionType(original.getInventoryTransactionId(), InventoryTransactionType.REVERSAL.code())
                .isPresent();
            if (alreadyReversed || InventoryTransactionType.REVERSAL.code().equals(original.getTransactionType())) {
                continue;
            }
            apply(new InventoryMovement(
                original.getInventoryId(),
                InventoryTransactionType.REVERSAL,
                negate(original.getQuantityDelta()),
                negate(original.getReservedDelta()),
                "inventory_transaction",
                original.getInventoryTransactionId(),
                reason,
                null,
                actorUserId
            ));
            reversed++;
        }
        return reversed;
    }

    private static BigDecimal negate(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.negate();
    }

    /**
     * Recomputes a LOT's status from all its stock rows; quarantined and closed LOTs keep their status. This transaction
     * cannot see a concurrent movement on another record of the same LOT, so the LOT is checked again after commit
     * ({@link LotStatusResync}), once per LOT however many of its records moved.
     */
    @Transactional
    public void syncLotStatus(String lotId) {
        recheckAfterCommit(lotId);
        LotMaster lot = lotMasterRepository.findById(lotId).orElse(null);
        if (lot == null || QUARANTINED.equals(lot.getLotStatus()) || "closed".equals(lot.getLotStatus())
            || INSPECTION_PENDING.equals(lot.getLotStatus())) {
            return;
        }
        String status = lotStatusFor(inventoryRepository.findAllByLotIdAndDeletedYn(lotId, NOT_DELETED));
        if (!status.equals(lot.getLotStatus())) {
            lot.setLotStatus(status);
            lotMasterRepository.save(lot);
        }
    }

    /** consumed when nothing is on hand, reserved when some of it is held, else available. */
    static String lotStatusFor(List<Inventory> rows) {
        BigDecimal onHand = rows.stream().map(row -> zeroIfNull(row.getQuantity())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal reserved = rows.stream().map(row -> zeroIfNull(row.getReservedQuantity())).reduce(BigDecimal.ZERO, BigDecimal::add);
        return lotStatusFor(onHand, reserved);
    }

    static String lotStatusFor(BigDecimal onHand, BigDecimal reserved) {
        return onHand.signum() == 0 ? "consumed" : reserved.signum() > 0 ? "reserved" : AVAILABLE;
    }

    /** The LOTs this transaction touched, re-checked after it commits. Bound to the transaction's thread. */
    private static final Object LOTS_TO_RECHECK = new Object();

    @SuppressWarnings("unchecked")
    private void recheckAfterCommit(String lotId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        Set<String> lots = (Set<String>) TransactionSynchronizationManager.getResource(LOTS_TO_RECHECK);
        if (lots == null) {
            Set<String> pending = new LinkedHashSet<>();
            TransactionSynchronizationManager.bindResource(LOTS_TO_RECHECK, pending);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    for (String lot : pending) {
                        try {
                            lotStatusResync.resync(lot);
                        } catch (RuntimeException e) {
                            // The movement is committed and its own check stands; a missed re-check is logged, not thrown.
                            log.warn("LOT {} status re-check after commit failed", lot, e);
                        }
                    }
                }

                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(LOTS_TO_RECHECK);
                }
            });
            lots = pending;
        }
        lots.add(lotId);
    }

    /** Quarantine applies to the whole LOT when the row has one: every row of that LOT and the LOT itself. */
    private void changeQuarantine(Inventory inventory, boolean quarantine, OffsetDateTime now) {
        LotMaster waiting = inventory.getLotId() == null ? null
            : lotMasterRepository.findById(inventory.getLotId()).filter(lot -> INSPECTION_PENDING.equals(lot.getLotStatus())).orElse(null);
        if (waiting != null) {
            // Only quality releases a LOT that waits for its receipt checks (docs/domain/lot-release.md R4).
            if (!quarantine) {
                throw new BusinessException(ErrorCode.CONFLICT, "LOT " + waiting.getLotNo()
                    + " waits for its receipt checks; release it with Release LOT once they have passed.");
            }
            // A failed check or a recall turns the wait into a quarantine; the LOT's rows are held already (R5).
            waiting.setLotStatus(QUARANTINED);
            lotMasterRepository.save(waiting);
            return;
        }
        String target = quarantine ? QUARANTINED : AVAILABLE;
        boolean isQuarantined = QUARANTINED.equals(inventory.getInventoryStatus());
        if (quarantine == isQuarantined) {
            throw new BusinessException(ErrorCode.CONFLICT,
                quarantine ? "This stock is already quarantined." : "This stock is not quarantined.");
        }
        if (inventory.getLotId() == null) {
            inventoryRepository.updateStatus(inventory.getInventoryId(), target, now);
            return;
        }
        for (Inventory row : inventoryRepository.findAllByLotIdAndDeletedYn(inventory.getLotId(), NOT_DELETED)) {
            inventoryRepository.updateStatus(row.getInventoryId(), target, now);
        }
        lotMasterRepository.findById(inventory.getLotId()).ifPresent(lot -> {
            lot.setLotStatus(target);
            lotMasterRepository.save(lot);
        });
        if (!quarantine) {
            // Released: the LOT goes back to whatever its stock says (available / reserved / consumed).
            syncLotStatus(inventory.getLotId());
        }
    }

    /** Explains why the conditional UPDATE changed nothing, using the current row. */
    private BusinessException rejection(
        String inventoryId,
        InventoryTransactionType type,
        BigDecimal quantityDelta,
        BigDecimal reservedDelta
    ) {
        Inventory current = inventoryRepository.findByInventoryIdAndDeletedYn(inventoryId, NOT_DELETED).orElse(null);
        if (current == null) {
            return new BusinessException(ErrorCode.NOT_FOUND, "The stock record no longer exists.");
        }
        if (type.blockedByQuarantine() && QUARANTINED.equals(current.getInventoryStatus())) {
            return new BusinessException(ErrorCode.CONFLICT, "This stock is quarantined. Release it before using it.");
        }
        BigDecimal onHand = zeroIfNull(current.getQuantity());
        BigDecimal reserved = zeroIfNull(current.getReservedQuantity());
        BigDecimal available = onHand.subtract(reserved);
        if (reservedDelta.signum() < 0 && reserved.add(reservedDelta).signum() < 0) {
            return new BusinessException(ErrorCode.CONFLICT,
                "Only " + plain(reserved) + " is reserved; cannot release " + plain(reservedDelta.negate()) + ".");
        }
        BigDecimal needed = reservedDelta.subtract(quantityDelta);
        return new BusinessException(ErrorCode.CONFLICT,
            "Not enough available stock: " + plain(available) + " available, " + plain(needed) + " needed.");
    }

    /**
     * Releases a LOT that waits for its receipt checks (docs/domain/lot-release.md R3): each held row becomes available with
     * an `unquarantine` movement referencing the LOT, and the LOT takes its stock's status. Quality calls this after the
     * checks passed; a plain unquarantine refuses such a LOT. Answers the LOT's status afterwards.
     */
    @Transactional
    public String releaseInspection(String lotId, String actorUserId) {
        lockLotExclusively(lotId);
        LotMaster lot = lotMasterRepository.findForUpdate(lotId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "LOT does not exist."));
        // The caller may have loaded this LOT before a concurrent failed inspection committed.
        entities.refresh(lot);
        if (!INSPECTION_PENDING.equals(lot.getLotStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "LOT " + lot.getLotNo() + " is " + lot.getLotStatus()
                + ", not waiting for its receipt checks.");
        }
        String lotNo = lot.getLotNo();
        OffsetDateTime now = OffsetDateTime.now();
        for (String inventoryId : inventoryRepository.findInventoryIdsToReleaseForLot(lotId)) {
            inventoryRepository.updateStatus(inventoryId, AVAILABLE, now);
            record(findActiveInventory(inventoryId), InventoryTransactionType.UNQUARANTINE, BigDecimal.ZERO, BigDecimal.ZERO,
                INSPECTION_RELEASE, lotId, "Released after the receipt checks of LOT " + lotNo, null, actorUserId);
        }
        InventoryRepository.LotStockTotals stock = inventoryRepository.lotStockTotals(lotId);
        LotMaster current = lotMasterRepository.findById(lotId).orElseThrow();
        current.setLotStatus(stock.getStockRows() == 0 ? AVAILABLE
            : lotStatusFor(stock.getQuantityOnHand(), stock.getQuantityReserved()));
        lotMasterRepository.saveAndFlush(current);
        return current.getLotStatus();
    }

    /**
     * Holds every stock record of a LOT that is not held yet, with a `quarantine` movement each, because the reopened LOT
     * waits for its receipt checks again (docs/domain/lot-release.md R6).
     */
    @Transactional
    public void holdForReceiptChecks(String lotId, String lotNo, String actorUserId) {
        OffsetDateTime now = OffsetDateTime.now();
        for (String inventoryId : inventoryRepository.findInventoryIdsToHoldForLot(lotId)) {
            inventoryRepository.updateStatus(inventoryId, QUARANTINED, now);
            record(findActiveInventory(inventoryId), InventoryTransactionType.QUARANTINE, BigDecimal.ZERO, BigDecimal.ZERO,
                LOT_REOPEN, lotId, "Held for the receipt checks of reopened LOT " + lotNo, null, actorUserId);
        }
    }

    /** Quantity writers may run together, while closing, reopening and quarantine exclude them. */
    void lockLotForStock(LotMaster lot) {
        if (!lotMasterRepository.tryStockLifecycleLock(lot.getLotId())) throw busyLot();
        refreshLot(lot, false);
    }

    void lockLot(LotMaster lot) {
        lockLotExclusively(lot.getLotId());
        refreshLot(lot, true);
    }

    private void lockLotExclusively(String lotId) {
        if (!lotMasterRepository.tryExclusiveLifecycleLock(lotId)) throw busyLot();
    }

    private void refreshLot(LotMaster lot, boolean exclusive) {
        LockModeType held = entities.getLockMode(lot);
        // A lifecycle operation may already have unsaved edits in this same persistence context.
        if (held == LockModeType.PESSIMISTIC_WRITE || held == LockModeType.PESSIMISTIC_FORCE_INCREMENT) return;
        try {
            if (exclusive) {
                entities.refresh(lot, LockModeType.PESSIMISTIC_WRITE, Map.of("jakarta.persistence.lock.timeout", 0));
            } else {
                entities.refresh(lot);
            }
        } catch (LockTimeoutException | PessimisticLockException conflict) {
            throw busyLot();
        }
    }

    private BusinessException busyLot() {
        return new BusinessException(ErrorCode.CONFLICT, "LOT is being changed. Reload and try again.");
    }

    private Inventory findActiveInventory(String inventoryId) {
        return inventoryRepository.findByInventoryIdAndDeletedYn(inventoryId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static String trimToNull(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }
}
