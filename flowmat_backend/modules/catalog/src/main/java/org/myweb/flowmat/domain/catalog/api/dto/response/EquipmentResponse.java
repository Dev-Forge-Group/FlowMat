package org.myweb.flowmat.domain.catalog.api.dto.response;

import org.myweb.flowmat.domain.catalog.api.dto.request.EquipmentDetails;

public record EquipmentResponse(
    String equipmentId,
    String projectId,
    String equipmentCode,
    String equipmentName,
    String equipmentType,
    String equipmentStatus,
    /** Never null; fields not recorded are null. */
    EquipmentDetails details
) {
}
