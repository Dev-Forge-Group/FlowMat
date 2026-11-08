package org.myweb.flowmat.domain.quality.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Closes a defect with what was done about it, optionally writing the defective stock off in the same transaction
 * (docs/domain/quality-inspection.md "폐기 처리").
 *
 * @param scrapInventoryId the stock record to write off from: same item, and same LOT when the defect names one.
 * @param scrapQuantity    how much to write off; given together with {@code scrapInventoryId}.
 */
public record DefectResolveRequest(
    @NotBlank @Size(max = 2000) String actionTaken,
    String scrapInventoryId,
    @Positive BigDecimal scrapQuantity
) {
}
