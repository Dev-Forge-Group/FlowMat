package org.myweb.flowmat.domain.inventory.api.dto.response;

import java.time.OffsetDateTime;

/** @param version null while the project has no settings, which then warn only */
public record InventorySettingResponse(String projectId, String storageConditionPolicy, Long version, String updatedBy, OffsetDateTime updatedAt) {
}
