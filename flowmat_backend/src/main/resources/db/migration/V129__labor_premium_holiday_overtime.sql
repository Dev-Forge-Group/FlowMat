-- V129: Holiday overtime in the pay premiums (docs/architecture/adr/ADR-028-pay-premiums.md decisions 18-19): on a holiday, or a
-- rest day paid like one, the minutes past a number get a percent more on top of the holiday percent, in place of the daily
-- overtime. It needs a holiday percent. Existing rules have none.
ALTER TABLE labor_premium_rule
    ADD COLUMN holiday_overtime_after_minutes integer CHECK (holiday_overtime_after_minutes BETWEEN 1 AND 1440),
    ADD COLUMN holiday_overtime_percent integer CHECK (holiday_overtime_percent BETWEEN 1 AND 500),
    ADD CONSTRAINT ck_labor_premium_rule_holiday_overtime CHECK (
        (holiday_overtime_after_minutes IS NULL AND holiday_overtime_percent IS NULL)
        OR (holiday_overtime_after_minutes IS NOT NULL AND holiday_overtime_percent IS NOT NULL AND holiday_percent IS NOT NULL));
