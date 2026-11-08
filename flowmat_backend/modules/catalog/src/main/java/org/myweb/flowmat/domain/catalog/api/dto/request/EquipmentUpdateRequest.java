package org.myweb.flowmat.domain.catalog.api.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

public record EquipmentUpdateRequest(
    @Size(max = 100) String equipmentName,
    @Size(max = 50) String equipmentType,
    @Size(max = 20) String equipmentStatus,
    /** Omitted: every detail unchanged. Present: replaces them all, so a null field is cleared. */
    @Valid EquipmentDetails details,
    /** Why the status changes, kept in its history (docs/domain/equipment.md "상태 이력"); ignored when it stays the same. */
    @Size(max = 200) String statusNote
) {

    public EquipmentUpdateRequest(String equipmentName, String equipmentType, String equipmentStatus, EquipmentDetails details) {
        this(equipmentName, equipmentType, equipmentStatus, details, null);
    }
}
