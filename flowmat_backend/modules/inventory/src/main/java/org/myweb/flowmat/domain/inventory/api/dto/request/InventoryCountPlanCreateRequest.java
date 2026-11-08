package org.myweb.flowmat.domain.inventory.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record InventoryCountPlanCreateRequest(
    @NotBlank @Size(max = 50) String projectId,
    @NotNull UUID requestId,
    boolean blind,
    @Size(max = 500) String note,
    @NotEmpty @Size(max = 500) List<@NotBlank @Size(max = 50) String> inventoryIds
) {}
