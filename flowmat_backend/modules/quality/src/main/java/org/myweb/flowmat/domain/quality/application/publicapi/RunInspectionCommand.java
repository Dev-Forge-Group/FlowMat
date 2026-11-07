package org.myweb.flowmat.domain.quality.application.publicapi;

import java.math.BigDecimal;

/**
 * Records a measurement another bounded context took as a quality inspection (docs/domain/inspection-standard.md), instead
 * of writing to the quality repositories (docs/architecture/adr/ADR-002-module-dependency.md).
 */
public interface RunInspectionCommand {

    /**
     * Records a run's inspection against an inspection standard of its product, as the quality screen would (S5-S7): the
     * standard sets the check, its limits and unit. With limits the measured value decides the result (send no result);
     * without them a person gives {@code result} (pass or fail). The caller has checked that the standard is active and
     * for production, so this does not refuse an ordinary inspection.
     */
    RecordedInspection recordForRun(String projectId, String productionRunId, String standardId, String inspectionType,
                                    BigDecimal measuredValue, String result, String note);

    /** @param result pass or fail */
    record RecordedInspection(String inspectionId, String result) {
    }
}
