package org.myweb.flowmat.domain.production.api.dto.response;

import java.math.BigDecimal;

public record ProductionRunResponse(
    String productionRunId,
    String projectId,
    String workflowId,
    String workflowRevisionId,
    String runNumber,
    String runType,
    String runStatus,
    String targetItemId,
    BigDecimal plannedOutputQty,
    BigDecimal actualOutputQty,
    String workOrderId,
    String bomId,
    Integer bomVersion,
    java.time.OffsetDateTime actualEndAt
) {
}
