package org.myweb.flowmat.domain.inventory.application.publicapi;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The stock alert settings (docs/domain/stock-alert.md), owned by inventory and read through its public API by any
 * context that reasons about the same thresholds, so stock alerts and work order readiness cannot disagree on them.
 * Bound once from {@code app.stock-alert.*}; a value out of range stops the application at startup.
 *
 * @param expiryWarningDays how many days ahead of a LOT's expiry date its stock raises an expiry alert (0 to 3650)
 * @param sweepInterval how often the safety-net sweep re-evaluates every stock row (positive)
 */
@Validated
@ConfigurationProperties(prefix = "app.stock-alert")
public record StockAlertSettings(
    @DefaultValue("7") @Min(0) @Max(3650) int expiryWarningDays,
    @DefaultValue("PT5M") @NotNull Duration sweepInterval
) {

    @AssertTrue(message = "app.stock-alert.sweep-interval must be positive")
    public boolean isSweepIntervalPositive() {
        return sweepInterval == null || (!sweepInterval.isNegative() && !sweepInterval.isZero());
    }
}
