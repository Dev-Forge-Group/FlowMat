package org.myweb.flowmat.domain.quality.application.publicapi;

import java.math.BigDecimal;

/**
 * What another bounded context may read about an inspection standard (docs/domain/inspection-standard.md).
 *
 * @param stage  receipt, production or any
 * @param active active and not deleted
 */
public record InspectionStandardView(
    String standardId,
    String projectId,
    String itemId,
    String inspectionType,
    String stage,
    BigDecimal standardMin,
    BigDecimal standardMax,
    String unit,
    boolean active
) {
}
