package org.myweb.flowmat.domain.production.api.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * How much of a BOM's product the usable stock could make right now (docs/domain/material-requirements.md "지금 만들 수
 * 있는 양"). Nothing is reserved by asking.
 *
 * @param targetUnit the product's unit, which {@code baseQuantity} and every buildable quantity are in
 * @param baseQuantity what one batch of the BOM makes
 * @param buildable the least of the lines' buildable quantities; whole units for a counted product; null when the BOM has
 *     no materials
 * @param limitingItemId the material that runs out first; null when the BOM has no materials
 * @param lines materials in BOM order
 * @param problem why the BOM could not be worked out, in the project-wide list only; null otherwise
 */
public record BuildableQuantityResponse(
    String bomId,
    String targetItemId,
    String targetUnit,
    BigDecimal baseQuantity,
    BigDecimal buildable,
    String limitingItemId,
    List<Line> lines,
    String problem
) {

    /**
     * Quantities of the material are in its own unit.
     *
     * @param perBatch needed for one batch of the BOM
     * @param usable available (on hand − reserved) outside quarantine and closed or expired LOTs, never below zero
     * @param buildable how much of the product this material alone allows, in the product's unit
     */
    public record Line(String childItemId, String itemUnit, BigDecimal perBatch, BigDecimal usable, BigDecimal buildable) {
    }
}
