package org.myweb.flowmat.domain.workflow.api.dto.response;

import java.math.BigDecimal;

public record ProcessConnectionResponse(
    String connectionId,
    String projectId,
    String workflowId,
    String fromProcessId,
    String toProcessId,
    String fromIoId,
    String toIoId,
    String itemId,
    String sourceHandle,
    String targetHandle,
    String connectionType,
    String connectionLabel,
    BigDecimal flowRate,
    String unit,
    BigDecimal delayTimeSec,
    /** elapsed or working: what the delay counts in production runs (ADR-022). */
    String delayBasis,
    BigDecimal lossRate,
    Integer priority,
    String conditionExpr,
    BigDecimal capacity,
    String failurePolicy,
    /** An Exclusive gateway's default connection (ADR-006). */
    boolean defaultRoute,
    /** A Split gateway's share (ADR-008): share, fixed or rest, and its percent or quantity; null for other connections. */
    String splitKind,
    BigDecimal splitValue,
    int version,
    int versionNonce
) {
}
