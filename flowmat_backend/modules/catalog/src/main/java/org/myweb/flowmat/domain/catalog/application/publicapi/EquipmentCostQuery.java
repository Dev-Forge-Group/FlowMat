package org.myweb.flowmat.domain.catalog.application.publicapi;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/** Current planning rates, never actual execution costs. The caller checks project read access. */
public interface EquipmentCostQuery {
    /** Only live equipment in this project; unset rates are absent. Zero is a known rate. */
    Map<String, BigDecimal> findHourlyCosts(String projectId, Collection<String> equipmentIds);

    /**
     * One live equipment's current rate with its version, for an actual-cost snapshot (docs/domain/equipment-setup-cost.md
     * AS2). Empty when the equipment is not live in this project; a rate never set or cleared is null.
     */
    Optional<EquipmentRate> findHourlyRate(String projectId, String equipmentId);

    /** A rate as it stood when read; {@code version} 0 means none was ever saved. */
    record EquipmentRate(String equipmentId, BigDecimal hourlyCost, long version) {
    }

    /**
     * One live equipment's rate at an instant, from its rate history (docs/domain/equipment-setup-cost.md AS8-AS9). Before
     * the first recorded change, or with no instant, it is the rate as first known and {@code estimated}.
     */
    Optional<EquipmentRateAt> findHourlyRateAt(String projectId, String equipmentId, OffsetDateTime at);

    /** A rate at an instant; {@code estimated} when the history does not cover the instant. */
    record EquipmentRateAt(String equipmentId, BigDecimal hourlyCost, long version, boolean estimated) {
    }
}
