package org.myweb.flowmat.domain.flowrun.application.publicapi;

import java.time.Instant;

/** A stretch [start, end) when a work order's equipment and crew work together (docs/architecture/adr/ADR-041-try-it-time.md). */
public record WorkingStretch(Instant start, Instant end) {
}
