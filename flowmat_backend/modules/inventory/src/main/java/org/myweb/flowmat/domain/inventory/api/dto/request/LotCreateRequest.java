package org.myweb.flowmat.domain.inventory.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Registers a LOT for a LOT-managed item. The LOT gets stock through POST /inventories with this lotId. */
public record LotCreateRequest(
    @NotBlank String projectId,
    @NotBlank String itemId,
    @NotBlank @Size(max = 100) String lotNo,
    String serialNo,
    OffsetDateTime receivedAt,
    LocalDate expiryDate
) {
}
