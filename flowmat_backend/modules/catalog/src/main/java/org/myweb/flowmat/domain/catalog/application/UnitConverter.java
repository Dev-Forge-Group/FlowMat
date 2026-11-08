package org.myweb.flowmat.domain.catalog.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.domain.entity.UnitMaster;
import org.myweb.flowmat.domain.catalog.repository.UnitMasterRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * Converts quantities recorded in any unit into the unit an item is stocked in.
 *
 * <p>Policy: units of different types (mass vs length) never convert; units of the same type convert through
 * their shared base unit (500 g → 0.5 kg). Items without a unit keep the pre-unit-master behaviour and take the
 * recorded quantity as-is.
 */
@Component
@RequiredArgsConstructor
public class UnitConverter {

    /** Intermediate precision; callers round to their column scale. */
    private static final int SCALE = 8;

    private final UnitMasterRepository unitMasterRepository;

    public record Conversion(BigDecimal quantity, String fromUnitCode, String toUnitCode, boolean converted) {
    }

    public Conversion toItemUnit(BigDecimal quantity, String recordedUnitCode, String itemUnitId) {
        String recorded = recordedUnitCode == null ? "" : recordedUnitCode.trim();
        if (itemUnitId == null || itemUnitId.isBlank()) {
            return new Conversion(quantity, recorded, recorded, false);
        }
        UnitMaster itemUnit = unitMasterRepository.findById(itemUnitId).orElse(null);
        if (itemUnit == null) {
            return new Conversion(quantity, recorded, recorded, false);
        }
        UnitMaster from = unitMasterRepository.findByUnitCodeIgnoreCase(recorded)
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST,
                "Unknown unit '" + recorded + "'. Use a unit from the unit master (this item is measured in "
                    + itemUnit.getUnitCode() + ")."));
        if (!from.getUnitType().equals(itemUnit.getUnitType())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "Cannot record " + from.getUnitCode() + " (" + from.getUnitType() + ") for an item measured in "
                    + itemUnit.getUnitCode() + " (" + itemUnit.getUnitType() + ").");
        }
        if (from.getUnitCode().equalsIgnoreCase(itemUnit.getUnitCode()) || quantity == null) {
            return new Conversion(quantity, from.getUnitCode(), itemUnit.getUnitCode(), false);
        }
        BigDecimal converted = quantity.multiply(rate(from)).divide(rate(itemUnit), SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
        return new Conversion(converted, from.getUnitCode(), itemUnit.getUnitCode(), true);
    }

    private static BigDecimal rate(UnitMaster unit) {
        BigDecimal rate = unit.getConversionRate();
        return rate == null || rate.signum() <= 0 ? BigDecimal.ONE : rate;
    }
}
