-- V92: A production step past its process's time limit is only marked late, not failed, since a person works it
-- (docs/domain/flow-run.md F12, user decision 2026-10-18). Graph runs outside production still time out (EP7).
ALTER TABLE flow_run_step_attempt
    ADD COLUMN overdue_at timestamptz,
    ADD CONSTRAINT ck_flow_run_step_attempt_overdue CHECK (overdue_at IS NULL OR (timeout_at IS NOT NULL AND overdue_at >= timeout_at));
