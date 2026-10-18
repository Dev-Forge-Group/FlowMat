package org.myweb.flowmat.domain.inventory.application.publicapi;

/** What another bounded context may read about a LOT. {@code productionRunId} is the run that made it, null for a bought LOT. */
public record LotView(
    String lotId,
    String projectId,
    String itemId,
    String lotNo,
    String lotStatus,
    String productionRunId
) {
}
