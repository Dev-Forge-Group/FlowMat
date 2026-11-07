package org.myweb.flowmat.domain.inventory.application;

import java.math.BigDecimal;
import java.util.List;

/**
 * The stock allocations of a work order, as inventory's pick and allocated-transfer flows need them. Called by inventory
 * inside its movement transaction; lock production before locking stock. Production owns the allocations and implements
 * this, so inventory does not depend on production (production already depends on inventory).
 */
public interface WorkOrderAllocations {
    record LockedAllocation(String inventoryId, String itemId, String lotId, BigDecimal remaining, boolean movable) {}
    record OpenAllocation(String allocationId, String inventoryId, String itemId, BigDecimal remaining) {}
    List<OpenAllocation> lockOpen(String projectId, String workOrderId);
    LockedAllocation lock(String projectId, String workOrderId, String allocationId);
    void move(String projectId, String workOrderId, String allocationId, String sourceInventoryId, String destinationInventoryId, BigDecimal quantity);
}
