package org.myweb.flowmat.domain.inventory.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.api.dto.request.InventoryCountRequest;
import org.myweb.flowmat.domain.inventory.api.dto.response.InventoryCountResponse;
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

/**
 * Stock counts (docs/domain/stock-count.md): each counted record is adjusted to what was counted, all in one transaction,
 * through the ordinary adjustment movement. Rows are locked before the difference is worked out, so a movement arriving
 * at the same moment cannot make the result differ from the count.
 */
@Service
@RequiredArgsConstructor
public class InventoryCountService {

    static final String REFERENCE_TYPE = "inventory_count";
    private static final String NOT_DELETED = "N";

    private final InventoryRepository inventoryRepository;
    private final InventoryTransactionRepository inventoryTransactionRepository;
    private final InventoryCommandService inventoryCommandService;
    private final ProjectAccessService projectAccessService;
    private final IdGenerator idGenerator;

    @Transactional
    public InventoryCountResponse count(InventoryCountRequest request) {
        String projectId = request.projectId().trim();
        projectAccessService.requireProjectWriteAccess(projectId);
        String actor = projectAccessService.requireCurrentUserId();
        String requestId = request.requestId().trim();
        Set<String> seen = new HashSet<>();
        for (InventoryCountRequest.Line line : request.lines()) {
            if (!seen.add(line.inventoryId())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "A stock record is counted twice in the same count.");
            }
            if (line.countedQuantity() == null || line.countedQuantity().signum() < 0) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "A counted quantity cannot be negative.");
            }
        }

        // A retry of a count that went through returns what it did.
        List<InventoryTransaction> previous = request.lines().stream()
            .map(line -> inventoryTransactionRepository.findByProjectIdAndRequestId(projectId, lineKey(requestId, line.inventoryId())))
            .flatMap(Optional::stream)
            .toList();
        if (!previous.isEmpty()) {
            return replay(previous);
        }

        String countId = idGenerator.generate();
        String note = request.note() == null || request.note().isBlank() ? "Stock count" : "Stock count: " + request.note().trim();
        List<InventoryCountResponse.Line> results = new ArrayList<>();
        // Lock in a fixed order so two counts over the same records cannot deadlock.
        List<InventoryCountRequest.Line> ordered = request.lines().stream()
            .sorted(Comparator.comparing(InventoryCountRequest.Line::inventoryId))
            .toList();
        for (InventoryCountRequest.Line line : ordered) {
            Inventory row = inventoryRepository.findForUpdate(line.inventoryId())
                .filter(found -> NOT_DELETED.equals(found.getDeletedYn()) && projectId.equals(found.getProjectId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "A counted stock record was not found in this project."));
            BigDecimal before = zeroIfNull(row.getQuantity());
            if (line.expectedQuantity() != null && before.compareTo(line.expectedQuantity()) != 0) {
                throw new BusinessException(ErrorCode.CONFLICT, "The stock at " + place(row) + " changed from "
                    + plain(line.expectedQuantity()) + " to " + plain(before) + " since the count started. Count it again.");
            }
            BigDecimal difference = line.countedQuantity().subtract(before);
            String transactionId = null;
            if (difference.signum() != 0) {
                BigDecimal reserved = zeroIfNull(row.getReservedQuantity());
                if (line.countedQuantity().compareTo(reserved) < 0) {
                    throw new BusinessException(ErrorCode.CONFLICT, "The count at " + place(row) + " (" + plain(line.countedQuantity())
                        + ") is below the " + plain(reserved) + " reserved there. Release the reservation first.");
                }
                transactionId = inventoryCommandService.apply(new InventoryMovement(row.getInventoryId(),
                    InventoryTransactionType.ADJUSTMENT, difference, BigDecimal.ZERO, REFERENCE_TYPE, countId, note,
                    lineKey(requestId, row.getInventoryId()), actor)).getInventoryTransactionId();
            }
            results.add(new InventoryCountResponse.Line(row.getInventoryId(), row.getItemId(), row.getLocation(), row.getLotId(),
                before, line.countedQuantity(), difference, transactionId));
        }
        // Every counted record was checked, also those already right, which leave no movement behind.
        inventoryRepository.markChecked(results.stream().map(InventoryCountResponse.Line::inventoryId).toList(), OffsetDateTime.now(), actor);
        int adjusted = (int) results.stream().filter(line -> line.inventoryTransactionId() != null).count();
        return new InventoryCountResponse(countId, adjusted, results.size() - adjusted, results);
    }

    private InventoryCountResponse replay(List<InventoryTransaction> recorded) {
        String countId = recorded.get(0).getReferenceId();
        List<InventoryCountResponse.Line> lines = inventoryTransactionRepository.findAllByReferenceTypeAndReferenceId(REFERENCE_TYPE, countId)
            .stream()
            .map(tx -> new InventoryCountResponse.Line(tx.getInventoryId(), tx.getItemId(), null, tx.getLotId(),
                zeroIfNull(tx.getQuantityAfter()).subtract(zeroIfNull(tx.getQuantityDelta())), tx.getQuantityAfter(),
                tx.getQuantityDelta(), tx.getInventoryTransactionId()))
            .toList();
        return new InventoryCountResponse(countId, lines.size(), 0, lines);
    }

    /** The per-record idempotency key: the count's key plus the record, so each adjustment has its own. */
    private static String lineKey(String requestId, String inventoryId) {
        return requestId + ":" + inventoryId;
    }

    private static String place(Inventory row) {
        return row.getLocation() == null ? "no location" : row.getLocation();
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
