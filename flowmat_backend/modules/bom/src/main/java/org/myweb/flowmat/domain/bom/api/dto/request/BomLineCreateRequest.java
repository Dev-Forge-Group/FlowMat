package org.myweb.flowmat.domain.bom.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * One material line; draft revisions only. scrapRate is planned scrap from 0 to 1 on a material line (ADR-031).
 * A substitute joins the group of the line it stands in for, through substituteFor, or names the group itself with
 * substituteGroup and priority (ADR-032 decision 3). optionalYn "Y" makes it an optional material, planned only for a work
 * order that picks it (ADR-033).
 */
public record BomLineCreateRequest(
    @NotBlank String childItemId,
    @NotNull BigDecimal quantity,
    @NotBlank String unit,
    BigDecimal scrapRate,
    String optionalYn,
    String substituteGroup,
    Integer sortOrder,
    String note,
    /** material (default), by_product or waste (docs/domain/bom-by-products.md). */
    String lineType,
    /** true: a phantom sub-assembly, used through its own BOM (docs/domain/multi-level-bom.md P1); material lines only. */
    Boolean phantom,
    /** The line of this revision the new material stands in for; it then goes last in that line's substitute group. */
    String substituteFor,
    /** With substituteGroup: the order of preference in the group, lowest first; blank goes last. */
    Integer priority
) {

    public BomLineCreateRequest(String childItemId, BigDecimal quantity, String unit, BigDecimal scrapRate, String optionalYn,
                                String substituteGroup, Integer sortOrder, String note, String lineType) {
        this(childItemId, quantity, unit, scrapRate, optionalYn, substituteGroup, sortOrder, note, lineType, null);
    }

    public BomLineCreateRequest(String childItemId, BigDecimal quantity, String unit, BigDecimal scrapRate, String optionalYn,
                                String substituteGroup, Integer sortOrder, String note, String lineType, Boolean phantom) {
        this(childItemId, quantity, unit, scrapRate, optionalYn, substituteGroup, sortOrder, note, lineType, phantom, null, null);
    }
}
