package org.myweb.flowmat.domain.quality.application.publicapi;

/** What quality reads about one recorded input or output of a production run. */
public record ProductionRunRecordingView(
    String itemId,
    String lotId,
    String direction
) {
}
