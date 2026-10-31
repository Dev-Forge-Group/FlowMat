-- V130: A worker's own pay premiums (docs/architecture/adr/ADR-028-pay-premiums.md decisions 20-22): a rule with a user_id is that
-- worker's, in its own history from a project date, and wins over the project's; one marked follow_project_yn returns the worker to
-- the project's from its date and has no premiums. Existing rules are the project's.
ALTER TABLE labor_premium_rule
    ADD COLUMN user_id varchar(50),
    ADD COLUMN follow_project_yn char(1) NOT NULL DEFAULT 'N' CHECK (follow_project_yn IN ('Y', 'N')),
    ADD CONSTRAINT ck_labor_premium_rule_follow CHECK (follow_project_yn = 'N' OR (user_id IS NOT NULL
        AND overtime_after_minutes IS NULL AND night_start IS NULL AND holiday_percent IS NULL AND weekly_overtime_after_minutes IS NULL));
CREATE INDEX ix_labor_premium_rule_worker ON labor_premium_rule(project_id, user_id, effective_from) WHERE user_id IS NOT NULL;
