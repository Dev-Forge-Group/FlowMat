package org.myweb.flowmat.domain.inventory.application;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.ItemStatusRule;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.inventory.api.dto.request.InventoryReversalRequest;
import org.myweb.flowmat.domain.inventory.api.dto.request.InventoryTransactionCreateRequest;
import org.myweb.flowmat.domain.inventory.api.dto.response.InventoryTransactionResponse;
import org.myweb.flowmat.domain.inventory.domain.entity.Inventory;
import org.myweb.flowmat.domain.inventory.domain.entity.InventoryTransaction;
import org.myweb.flowmat.domain.inventory.domain.enums.InventoryTransactionType;
import org.myweb.flowmat.domain.inventory.repository.InventoryRepository;
import org.myweb.flowmat.domain.inventory.repository.InventoryTransactionRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryTransactionServiceImpl implements InventoryTransactionService {

    private static final String NOT_DELETED = "N";
    private static final int MAX_SCALE = 4;
    private static final String EXTERNAL_TYPES = Arrays.stream(InventoryTransactionType.values())
        .filter(InventoryTransactionType::external)
        .map(InventoryTransactionType::code)
        .collect(Collectors.joining(", "));

    private final InventoryTransactionRepository inventoryTransactionRepository;
    private final InventoryRepository inventoryRepository;
    private final CatalogQuery catalogQuery;
    private final InventoryCommandService inventoryCommandService;
    private final ProjectAccessService projectAccessService;

    @Override
    public List<InventoryTransactionResponse> listTransactions(String projectId, String inventoryId) {
        if (inventoryId != null && !inventoryId.isBlank()) {
            Inventory inventory = findActiveInventory(inventoryId);
            projectAccessService.requireProjectReadAccess(inventory.getProjectId());
            return inventoryTransactionRepository.findAllByInventoryIdOrderByCreatedAtDesc(inventoryId).stream()
                .map(InventoryTransactionServiceImpl::toResponse)
                .toList();
        }
        if (projectId != null && !projectId.isBlank()) {
            projectAccessService.requireProjectReadAccess(projectId);
            return inventoryTransactionRepository.findAllByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(InventoryTransactionServiceImpl::toResponse)
                .toList();
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST);
    }

    @Override
    @Transactional
    public InventoryTransactionResponse createTransaction(InventoryTransactionCreateRequest request) {
        Inventory inventory = findActiveInventory(request.inventoryId());
        projectAccessService.requireProjectWriteAccess(inventory.getProjectId());
        String actor = projectAccessService.requireCurrentUserId();

        InventoryTransactionType type = InventoryTransactionType.fromCode(request.transactionType())
            .filter(InventoryTransactionType::external)
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST,
                "Unknown transaction type '" + request.transactionType() + "'. Use one of: " + EXTERNAL_TYPES + "."));
        BigDecimal[] deltas = deltas(type, request.quantity(), request.direction());
        String requestId = request.requestId().trim();
        if (type == InventoryTransactionType.RECEIPT) {
            CatalogItemView item = catalogQuery.findItems(List.of(inventory.getItemId())).get(inventory.getItemId());
            if (item != null && !ItemStatusRule.isActive(item.itemStatus())) {
                throw new BusinessException(ErrorCode.CONFLICT, ItemStatusRule.refusal(item.itemCode(), item.itemStatus(), "receive stock"));
            }
        }

        InventoryTransaction previous = inventoryTransactionRepository
            .findByProjectIdAndRequestId(inventory.getProjectId(), requestId).orElse(null);
        if (previous != null) {
            if (sameMovement(previous, inventory.getInventoryId(), type, deltas, request.referenceType(), request.referenceId())) {
                return toResponse(previous);
            }
            throw new BusinessException(ErrorCode.CONFLICT,
                "requestId '" + requestId + "' was already used for a different stock movement.");
        }

        return toResponse(inventoryCommandService.apply(new InventoryMovement(
            inventory.getInventoryId(),
            type,
            deltas[0],
            deltas[1],
            request.referenceType(),
            request.referenceId(),
            request.note(),
            requestId,
            actor
        )));
    }

    @Override
    public InventoryTransactionResponse getTransaction(String inventoryTransactionId) {
        InventoryTransaction transaction = findTransaction(inventoryTransactionId);
        projectAccessService.requireProjectReadAccess(transaction.getProjectId());
        return toResponse(transaction);
    }

    @Override
    @Transactional
    public InventoryTransactionResponse reverseTransaction(String inventoryTransactionId, InventoryReversalRequest request) {
        InventoryTransaction original = findTransaction(inventoryTransactionId);
        projectAccessService.requireProjectWriteAccess(original.getProjectId());
        String actor = projectAccessService.requireCurrentUserId();

        InventoryTransactionType originalType = InventoryTransactionType.fromCode(original.getTransactionType()).orElse(null);
        if (originalType == InventoryTransactionType.PRODUCTION_INPUT || originalType == InventoryTransactionType.PRODUCTION_OUTPUT) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "Production movements are corrected on their production run, not reversed here; "
                    + "otherwise the run's records and LOT genealogy would no longer match the stock.");
        }
        if (originalType == null || !originalType.reversible()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "A " + original.getTransactionType() + " transaction cannot be reversed.");
        }

        String requestId = request.requestId().trim();
        InventoryTransaction previous = inventoryTransactionRepository
            .findByProjectIdAndRequestId(original.getProjectId(), requestId).orElse(null);
        if (previous != null) {
            if (InventoryTransactionType.REVERSAL.code().equals(previous.getTransactionType())
                && original.getInventoryTransactionId().equals(previous.getReferenceId())) {
                return toResponse(previous);
            }
            throw new BusinessException(ErrorCode.CONFLICT,
                "requestId '" + requestId + "' was already used for a different stock movement.");
        }
        inventoryTransactionRepository
            .findByReferenceIdAndTransactionType(original.getInventoryTransactionId(), InventoryTransactionType.REVERSAL.code())
            .ifPresent(existing -> {
                throw new BusinessException(ErrorCode.CONFLICT,
                    "This transaction was already reversed by " + existing.getCreatedBy() + ".");
            });

        // The reversal is checked against today's stock: stock already used cannot be un-received.
        return toResponse(inventoryCommandService.apply(new InventoryMovement(
            original.getInventoryId(),
            InventoryTransactionType.REVERSAL,
            negate(original.getQuantityDelta()),
            negate(original.getReservedDelta()),
            "inventory_transaction",
            original.getInventoryTransactionId(),
            request.reason(),
            requestId,
            actor
        )));
    }

    /** Signed {quantity, reserved} deltas for a positive requested quantity. */
    static BigDecimal[] deltas(InventoryTransactionType type, BigDecimal quantity, String direction) {
        if (type.changesStatusOnly()) {
            return new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO};
        }
        if (quantity == null || quantity.signum() <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "quantity must be greater than 0.");
        }
        if (quantity.stripTrailingZeros().scale() > MAX_SCALE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Quantities have at most " + MAX_SCALE + " decimal places.");
        }
        if (type == InventoryTransactionType.ADJUSTMENT) {
            String normalized = direction == null ? "" : direction.trim().toLowerCase();
            return switch (normalized) {
                case "increase" -> new BigDecimal[] {quantity, BigDecimal.ZERO};
                case "decrease" -> new BigDecimal[] {quantity.negate(), BigDecimal.ZERO};
                default -> throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "An adjustment needs direction 'increase' or 'decrease'.");
            };
        }
        return new BigDecimal[] {
            quantity.multiply(BigDecimal.valueOf(type.quantitySign())),
            quantity.multiply(BigDecimal.valueOf(type.reservedSign()))
        };
    }

    private static boolean sameMovement(
        InventoryTransaction previous,
        String inventoryId,
        InventoryTransactionType type,
        BigDecimal[] deltas,
        String referenceType,
        String referenceId
    ) {
        return previous.getInventoryId().equals(inventoryId)
            && type.code().equals(previous.getTransactionType())
            && sameAmount(previous.getQuantityDelta(), deltas[0])
            && sameAmount(previous.getReservedDelta(), deltas[1])
            && Objects.equals(previous.getReferenceType(), trimToNull(referenceType))
            && Objects.equals(previous.getReferenceId(), trimToNull(referenceId));
    }

    private static boolean sameAmount(BigDecimal stored, BigDecimal requested) {
        return (stored == null ? BigDecimal.ZERO : stored).compareTo(requested) == 0;
    }

    private InventoryTransaction findTransaction(String inventoryTransactionId) {
        return inventoryTransactionRepository.findByInventoryTransactionId(inventoryTransactionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private Inventory findActiveInventory(String inventoryId) {
        return inventoryRepository.findByInventoryIdAndDeletedYn(inventoryId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    static InventoryTransactionResponse toResponse(InventoryTransaction transaction) {
        return new InventoryTransactionResponse(
            transaction.getInventoryTransactionId(),
            transaction.getInventoryId(),
            transaction.getProjectId(),
            transaction.getItemId(),
            transaction.getTransactionType(),
            transaction.getQuantityDelta(),
            transaction.getReservedDelta(),
            transaction.getAvailableDelta(),
            transaction.getQuantityAfter(),
            transaction.getReservedAfter(),
            transaction.getAvailableAfter(),
            transaction.getReferenceType(),
            transaction.getReferenceId(),
            transaction.getNote(),
            transaction.getCreatedBy(),
            transaction.getCreatedAt(),
            transaction.getLotId(),
            transaction.getRequestId()
        );
    }

    private static BigDecimal negate(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.negate();
    }

    private static String trimToNull(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }
}
