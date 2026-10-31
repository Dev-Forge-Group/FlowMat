-- V128: Rest days in the pay premiums (docs/architecture/adr/ADR-028-pay-premiums.md decisions 16-17): Y pays work on a day
-- without any of the worker's scheduled time with the rule's holiday percent too, so it needs one. Existing rules do not.
ALTER TABLE labor_premium_rule
    ADD COLUMN rest_days_as_holidays_yn char(1) NOT NULL DEFAULT 'N' CHECK (rest_days_as_holidays_yn IN ('Y', 'N')),
    ADD CONSTRAINT ck_labor_premium_rule_rest_days CHECK (rest_days_as_holidays_yn = 'N' OR holiday_percent IS NOT NULL);
