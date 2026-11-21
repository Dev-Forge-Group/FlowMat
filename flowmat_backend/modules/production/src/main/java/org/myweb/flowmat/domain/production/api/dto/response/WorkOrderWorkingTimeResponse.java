package org.myweb.flowmat.domain.production.api.dto.response;

import java.util.List;

/**
 * When a work order's equipment and its full-share crew with shifts work together in a window
 * (docs/architecture/adr/ADR-041-try-it-time.md decision 5), as a production run of the order waits for them. Times are
 * ISO with the offset of {@code from}, such as {@code 2030-01-07T09:00:00+09:00}.
 *
 * @param allTime     nothing fixes the time (no equipment and no such crew): every delay counts all time, no step waits
 * @param equipmentId the work order's equipment; null without one
 * @param crew        the people on the crew with shifts whose time the stretches follow
 * @param stretches   {@code [from, until)} pairs, earliest first; none when the team never works together in the window
 */
public record WorkOrderWorkingTimeResponse(
    String workOrderId,
    String from,
    String until,
    boolean allTime,
    String equipmentId,
    List<String> crew,
    List<List<String>> stretches
) {
}
