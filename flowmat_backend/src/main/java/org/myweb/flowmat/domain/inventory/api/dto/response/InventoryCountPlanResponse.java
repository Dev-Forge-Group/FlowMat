package org.myweb.flowmat.domain.inventory.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** Baseline and recount-checkpoint quantities are null for every non-owner, including the plan's creator. */
public record InventoryCountPlanResponse(String planId, String projectId, boolean blind, String status, String note,
    String createdBy, OffsetDateTime createdAt, String submittedBy, OffsetDateTime submittedAt, String countId, List<Line> lines,
    String assignedTo) {
    public record Line(String lineId, String inventoryId, String itemId, String lotId, String location,
        BigDecimal baselineQuantity, BigDecimal checkpointQuantity, BigDecimal countedQuantity,
        boolean requiresRecount, String countedBy, OffsetDateTime countedAt, Long entryVersion) {}
}
