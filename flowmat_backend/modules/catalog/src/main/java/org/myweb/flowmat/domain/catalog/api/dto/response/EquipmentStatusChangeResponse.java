package org.myweb.flowmat.domain.catalog.api.dto.response;

import java.time.OffsetDateTime;

/** One status change of a piece of equipment; {@code previousStatus} is null when it was added (docs/domain/equipment.md). */
public record EquipmentStatusChangeResponse(
    String previousStatus,
    String equipmentStatus,
    String note,
    String changedBy,
    OffsetDateTime changedAt
) {
}
