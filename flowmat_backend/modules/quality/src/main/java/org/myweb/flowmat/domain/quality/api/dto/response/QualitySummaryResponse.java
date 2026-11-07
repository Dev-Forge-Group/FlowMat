package org.myweb.flowmat.domain.quality.api.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * The project's quality at a glance (docs/domain/quality-inspection.md "요약"), over an optional time window.
 *
 * @param passRate passed / inspections, 0–1 with 4 decimals; null without inspections.
 */
public record QualitySummaryResponse(
    long inspections,
    long passed,
    long failed,
    BigDecimal passRate,
    long openDefects,
    long resolvedDefects,
    /** Most frequent first. */
    List<DefectTypeCount> defectsByType,
    /** Checks that failed at least once, most failures first. */
    List<CheckCount> failuresByCheck,
    /** Items with a failed inspection or a defect in the window, most defects first. */
    List<ItemQuality> byItem
) {

    /** Only counts: defects of one type can be of items in different units, so their quantities are not added up. */
    public record DefectTypeCount(String defectType, long count, long open) {
    }

    public record CheckCount(String inspectionType, long inspections, long failed) {
    }

    /**
     * One item's inspections and defects. Defect quantities are in the item's own unit, so for one item they add up.
     *
     * @param defectQuantity the defects' quantities added up, in {@code unit}
     */
    public record ItemQuality(String itemId, String itemCode, String itemName, String unit, long inspections, long failed,
                              long defects, long openDefects, BigDecimal defectQuantity) {
    }
}
