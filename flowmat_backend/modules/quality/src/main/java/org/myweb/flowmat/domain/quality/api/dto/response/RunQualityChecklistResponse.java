package org.myweb.flowmat.domain.quality.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The checks the items a run makes should get, and how each went in this run (docs/domain/inspection-standard.md).
 *
 * @param required checks marked required
 * @param requiredPassed required checks whose latest inspection in this run passed
 * @param requiredMissing required checks with no inspection in this run yet
 * @param failed checks, required or not, whose latest inspection in this run failed
 */
public record RunQualityChecklistResponse(
    String productionRunId,
    int required,
    int requiredPassed,
    int requiredMissing,
    int failed,
    List<Line> lines
) {

    /**
     * @param status missing, pass or fail: the latest inspection of the run that followed this standard, or recorded the
     *               same check on the same item
     */
    public record Line(
        String standardId,
        String itemId,
        String itemCode,
        String itemName,
        String inspectionType,
        String stage,
        BigDecimal standardMin,
        BigDecimal standardMax,
        String unit,
        boolean required,
        String status,
        String inspectionId,
        BigDecimal measuredValue,
        OffsetDateTime inspectedAt
    ) {
    }
}
