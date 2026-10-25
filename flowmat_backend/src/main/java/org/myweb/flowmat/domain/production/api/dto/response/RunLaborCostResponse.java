package org.myweb.flowmat.domain.production.api.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What a run's worked time cost, for the project owner only (docs/architecture/adr/ADR-025-worker-labor-cost.md).
 *
 * @param cost         the known amounts of the records that count
 * @param costComplete false when some record has no rate on its day
 * @param lines        the records that count, oldest first
 */
public record RunLaborCostResponse(String productionRunId, int workedMinutes, BigDecimal cost, boolean costComplete,
                                   List<Person> people, List<Line> lines) {

    public record Person(String userId, int workedMinutes, BigDecimal cost, boolean costComplete) {
    }

    /**
     * @param rateDate  the project date whose rate prices it
     * @param rateBasis recorded (the day it was recorded) or run_end (added by a correction: the run's original end)
     * @param hourlyRate null when there is no rate that day; then {@code cost} is null too
     */
    public record Line(String runLaborId, String userId, int workedMinutes, LocalDate rateDate, String rateBasis,
                       BigDecimal hourlyRate, BigDecimal cost) {
    }
}
