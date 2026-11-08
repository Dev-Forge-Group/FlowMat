package org.myweb.flowmat.domain.inventory.application;

import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockReturnCommand;
import org.myweb.flowmat.domain.inventory.domain.enums.InventoryTransactionType;
import org.myweb.flowmat.domain.inventory.repository.InventoryRepository;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stock sent back to a supplier for purchasing (docs/architecture/adr/ADR-027-purchasing.md decision 12). */
@Service
@RequiredArgsConstructor
public class StockReturnService implements StockReturnCommand {

    private final InventoryRepository inventoryRepository;
    private final InventoryCommandService inventoryCommandService;
    private final ProjectAccess projectAccessService;

    @Override
    @Transactional
    public void giveBack(StockReturn stockReturn) {
        if (stockReturn.quantity() == null || stockReturn.quantity().signum() <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Send back more than 0.");
        }
        inventoryRepository.findByInventoryIdAndDeletedYn(stockReturn.inventoryId(), "N")
            .filter(row -> row.getProjectId().equals(stockReturn.projectId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "The stock record to send back was not found in this project."));
        inventoryCommandService.apply(new InventoryMovement(stockReturn.inventoryId(), InventoryTransactionType.ADJUSTMENT,
            stockReturn.quantity().negate(), BigDecimal.ZERO, stockReturn.referenceType(), stockReturn.referenceId(), stockReturn.note(), null,
            projectAccessService.requireCurrentUserId()));
    }
}
