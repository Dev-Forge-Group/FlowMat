-- V131: The worker minutes of a step plan's process split among people (docs/architecture/adr/ADR-023-labor-step-followups.md
-- decisions 11-13, docs/domain/worker-calendar.md LB29-LB30): when a process names people, its planned minutes are theirs added up.
-- The rows go with the process's plan row.
CREATE TABLE work_order_step_plan_person (
    plan_person_id varchar(50) PRIMARY KEY,
    plan_id varchar(50) NOT NULL REFERENCES work_order_step_plan(plan_id) ON DELETE CASCADE,
    user_id varchar(50) NOT NULL,
    sort_order integer NOT NULL CHECK (sort_order >= 0),
    planned_minutes integer NOT NULL CHECK (planned_minutes BETWEEN 1 AND 100000),
    CONSTRAINT uq_work_order_step_plan_person UNIQUE (plan_id, user_id)
);
