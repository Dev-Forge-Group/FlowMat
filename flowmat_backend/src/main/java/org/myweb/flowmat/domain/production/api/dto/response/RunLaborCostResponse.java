package org.myweb.flowmat.domain.production.api.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * What a run's worked time cost, for the project owner only (docs/architecture/adr/ADR-025-worker-labor-cost.md).
 *
 * @param cost             the known amounts of the records that count
 * @param costComplete     false when some record has no rate on its day
 * @param lines            the records that count, oldest first
 * @param premiumCost      the known amounts with the pay premiums (docs/architecture/adr/ADR-028-pay-premiums.md decision 10)
 * @param premiumsComplete false when a record with a rate has no attendance of its worker on its day, so it is at the rate alone,
 *                         or has clock times partly outside attendance, that part at the rate (decision 24)
 */
public record RunLaborCostResponse(String productionRunId, int workedMinutes, BigDecimal cost, boolean costComplete,
                                   List<Person> people, List<Line> lines, BigDecimal premiumCost, boolean premiumsComplete) {

    public record Person(String userId, int workedMinutes, BigDecimal cost, boolean costComplete, BigDecimal premiumCost,
                         boolean premiumsComplete) {
    }

    /**
     * @param rateDate  the project date whose rate prices it
     * @param rateBasis recorded (the day it was recorded), run_end (added by a correction: the run's original end) or worked
     *                  (the day its clock times start, LB31)
     * @param hourlyRate    null when there is no rate that day; then {@code cost} is null too
     * @param premiumFactor with clock times, the pay of those minutes over them (decision 24); otherwise the worker's paid
     *                      minutes over attended minutes that day, less what timed records took (decisions 10, 25); 4 decimals;
     *                      null without attendance
     * @param premiumCost   {@code cost} times that factor; {@code cost} without attendance
     * @param workedFrom    its clock times; null when only its minutes were recorded
     */
    public record Line(String runLaborId, String userId, int workedMinutes, LocalDate rateDate, String rateBasis,
                       BigDecimal hourlyRate, BigDecimal cost, BigDecimal premiumFactor, BigDecimal premiumCost,
                       OffsetDateTime workedFrom, OffsetDateTime workedTo) {
    }
}
