package org.myweb.flowmat.domain.production.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.application.WorkOrderAllocations;
import org.myweb.flowmat.domain.production.domain.entity.StockAllocation;
import org.myweb.flowmat.domain.production.repository.StockAllocationRepository;
import org.myweb.flowmat.domain.production.repository.WorkOrderRepository;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockRecordQuery;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class WorkOrderAllocationsImpl implements WorkOrderAllocations {
    private final StockAllocationRepository allocations;
    private final WorkOrderRepository orders;
    private final StockRecordQuery stocks;
    private final ProjectAccessService access;
    private final IdGenerator ids;
    @Override
    public List<OpenAllocation> lockOpen(String projectId, String workOrderId) {
        access.requireProjectWriteAccess(projectId);
        var order = orders.findForUpdate(workOrderId).filter(found -> projectId.equals(found.getProjectId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        allocations.lockKey("stock-allocation|" + projectId);
        if (!WorkOrderServiceImpl.status(order).acceptsRuns()) throw new BusinessException(ErrorCode.CONFLICT, "Pick only for an approved or started work order.");
        return allocations.findAllByWorkOrderIdAndStatusOrderByCreatedAtAscAllocationIdAsc(workOrderId, "open").stream()
            .filter(allocation -> allocation.remaining().signum() > 0)
            .map(allocation -> new OpenAllocation(allocation.getAllocationId(), allocation.getInventoryId(), allocation.getItemId(), allocation.remaining())).toList();
    }
    @Override
    public LockedAllocation lock(String projectId, String workOrderId, String allocationId) {
        access.requireProjectWriteAccess(projectId);
        var order = orders.findForUpdate(workOrderId).filter(found -> projectId.equals(found.getProjectId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        allocations.lockKey("stock-allocation|" + projectId);
        var allocation = find(projectId, workOrderId, allocationId);
        return new LockedAllocation(allocation.getInventoryId(), allocation.getItemId(), allocation.getLotId(), allocation.remaining(),
            "open".equals(allocation.getStatus()) && WorkOrderServiceImpl.status(order).acceptsRuns());
    }
    @Override
    public void move(String projectId, String workOrderId, String allocationId, String sourceInventoryId, String destinationInventoryId, BigDecimal quantity) {
        var locked = lock(projectId, workOrderId, allocationId);
        if (!locked.movable() || quantity == null || quantity.signum() <= 0 || quantity.compareTo(locked.remaining()) > 0
            || !sourceInventoryId.equals(locked.inventoryId()) || sourceInventoryId.equals(destinationInventoryId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "The allocation can no longer supply this reserved move.");
        }
        var destination = stocks.findProjectStock(projectId, destinationInventoryId)
            .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "The reserved destination is not in this project."));
        if (!locked.itemId().equals(destination.itemId()) || !Objects.equals(locked.lotId(), destination.lotId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "The allocation destination must have the same item and LOT.");
        }
        StockAllocation original = find(projectId, workOrderId, allocationId);
        if (original.getQuantity().compareTo(quantity) == 0) {
            // With no consumed/released history, a full move keeps the allocation identity and its positive quantity.
            original.setInventoryId(destinationInventoryId); allocations.saveAndFlush(original); return;
        }
        original.setQuantity(original.getQuantity().subtract(quantity));
        if (original.remaining().signum() == 0) { original.setStatus("closed"); original.setClosedAt(OffsetDateTime.now()); }
        allocations.saveAndFlush(original);
        StockAllocation moved = new StockAllocation(); moved.setAllocationId(ids.generate()); moved.setProjectId(projectId);
        moved.setWorkOrderId(workOrderId); moved.setInventoryId(destinationInventoryId); moved.setItemId(locked.itemId()); moved.setLotId(locked.lotId());
        moved.setQuantity(quantity); moved.setConsumedQuantity(BigDecimal.ZERO); moved.setReleasedQuantity(BigDecimal.ZERO);
        moved.setStatus("open"); moved.setCreatedBy(access.requireCurrentUserId()); moved.setCreatedAt(OffsetDateTime.now());
        allocations.saveAndFlush(moved);
    }
    private StockAllocation find(String projectId, String orderId, String allocationId) {
        return allocations.findById(allocationId).filter(found -> projectId.equals(found.getProjectId()) && orderId.equals(found.getWorkOrderId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
}
