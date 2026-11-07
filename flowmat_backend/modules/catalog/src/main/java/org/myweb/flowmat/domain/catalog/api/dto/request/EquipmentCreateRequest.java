package org.myweb.flowmat.domain.catalog.api.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EquipmentCreateRequest(
    @NotBlank @Size(max = 50) String projectId,
    @Size(max = 50) String equipmentCode,
    @NotBlank @Size(max = 100) String equipmentName,
    @NotBlank @Size(max = 50) String equipmentType,
    /** Optional; omitted leaves every detail empty. */
    @Valid EquipmentDetails details
) {
}
