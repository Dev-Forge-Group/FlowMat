package org.myweb.flowmat.domain.catalog.api.dto.request;

/** A changeover rule; a blank item means any item. {@code minutes} is 1 to 10080 (a week). */
public record EquipmentChangeoverRequest(
    String fromItemId,
    String toItemId,
    Integer minutes,
    String note
) {
}
