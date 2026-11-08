package org.myweb.flowmat.domain.catalog.application.publicapi;

import java.math.BigDecimal;

/**
 * Converts a recorded quantity into an item's own unit through the unit master, for other bounded contexts
 * (docs/architecture/adr/ADR-036-cross-context-contract-types.md decision 6).
 */
public interface UnitConversion {

    /**
     * {@code quantity} in {@code recordedUnitCode} as a quantity in the unit {@code itemUnitId}, at 8 decimals; callers
     * round to their column scale. Unconverted (the same quantity) when the item has no unit or its unit is no longer in
     * the master, when the two units are the same, and for no quantity. Refuses with 400 a recorded unit the master does
     * not have and one of another unit type than the item's.
     */
    Conversion toItemUnit(BigDecimal quantity, String recordedUnitCode, String itemUnitId);

    record Conversion(BigDecimal quantity, String fromUnitCode, String toUnitCode, boolean converted) {
    }
}
