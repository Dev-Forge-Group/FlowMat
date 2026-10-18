package org.myweb.flowmat.domain.production.application.publicapi;

/** What another bounded context may read about one recorded input or output of a production run. */
public record ProductionRunRecordingView(
    String itemId,
    String lotId,
    String direction
) {
}
