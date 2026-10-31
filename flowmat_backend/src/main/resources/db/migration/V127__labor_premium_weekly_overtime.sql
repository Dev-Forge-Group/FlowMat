-- V127: Weekly overtime in the pay premiums (docs/architecture/adr/ADR-028-pay-premiums.md decisions 13-15): the regular minutes
-- of a week past a number of minutes get a percent more, the week starting on an ISO day (1 Monday to 7 Sunday). Existing rules
-- have none.
ALTER TABLE labor_premium_rule
    ADD COLUMN weekly_overtime_after_minutes integer CHECK (weekly_overtime_after_minutes BETWEEN 1 AND 10080),
    ADD COLUMN weekly_overtime_percent integer CHECK (weekly_overtime_percent BETWEEN 1 AND 500),
    ADD COLUMN week_starts_on integer CHECK (week_starts_on BETWEEN 1 AND 7),
    ADD CONSTRAINT ck_labor_premium_rule_weekly CHECK (
        (weekly_overtime_after_minutes IS NULL AND weekly_overtime_percent IS NULL AND week_starts_on IS NULL)
        OR (weekly_overtime_after_minutes IS NOT NULL AND weekly_overtime_percent IS NOT NULL AND week_starts_on IS NOT NULL));
