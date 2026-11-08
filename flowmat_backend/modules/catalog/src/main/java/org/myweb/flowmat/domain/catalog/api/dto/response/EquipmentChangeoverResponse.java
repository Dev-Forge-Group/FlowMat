package org.myweb.flowmat.domain.catalog.api.dto.response;

/** A changeover rule; null item fields mean any item. */
public record EquipmentChangeoverResponse(
    String changeoverId,
    String equipmentId,
    String fromItemId,
    String fromItemCode,
    String fromItemName,
    String toItemId,
    String toItemCode,
    String toItemName,
    int minutes,
    String note
) {
}
