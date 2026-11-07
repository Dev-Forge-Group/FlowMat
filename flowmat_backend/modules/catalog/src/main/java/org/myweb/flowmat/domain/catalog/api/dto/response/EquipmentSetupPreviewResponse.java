package org.myweb.flowmat.domain.catalog.api.dto.response;

public record EquipmentSetupPreviewResponse(String equipmentId, String fromItemId, String toItemId,
    int minutes, String ruleType, String changeoverId) {
}
