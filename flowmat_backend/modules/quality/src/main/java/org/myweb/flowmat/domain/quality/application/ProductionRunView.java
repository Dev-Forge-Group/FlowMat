package org.myweb.flowmat.domain.quality.application;

/** What quality reads about a production run. {@code targetItemId} is the product it makes, null without one. */
public record ProductionRunView(
    String productionRunId,
    String projectId,
    String runNumber,
    String runStatus,
    String targetItemId
) {
}
