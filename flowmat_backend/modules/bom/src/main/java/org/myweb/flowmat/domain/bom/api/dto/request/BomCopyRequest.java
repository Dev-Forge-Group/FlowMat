package org.myweb.flowmat.domain.bom.api.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Starts another product's BOM from this one (docs/domain/inventory-bom-lot-contract.md §5 "BOM 복사").
 *
 * @param bomName the new BOM's name; the source's name when blank
 */
public record BomCopyRequest(@NotBlank String targetItemId, String bomName) {
}
