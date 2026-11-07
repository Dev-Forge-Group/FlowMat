package org.myweb.flowmat.domain.catalog.api.dto.request;

/** Changes a rule's time and note; the items stay (remove the rule and add another to change them). Blank note clears. */
public record EquipmentChangeoverUpdateRequest(
    Integer minutes,
    String note
) {
}
