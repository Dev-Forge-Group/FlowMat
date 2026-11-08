package org.myweb.flowmat.domain.production.api.dto.request;

import java.math.BigDecimal;

public record ProductionRunFinishRequest(BigDecimal actualOutputQty, String finishedBy) {
}
