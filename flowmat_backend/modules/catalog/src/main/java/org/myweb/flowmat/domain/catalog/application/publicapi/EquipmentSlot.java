package org.myweb.flowmat.domain.catalog.application.publicapi;

import java.time.OffsetDateTime;

/** A stretch of time on a piece of equipment, in the planning time zone. */
public record EquipmentSlot(OffsetDateTime start, OffsetDateTime end) {
}
