-- V124: Planned worker minutes for each process of a work order's workflow (docs/architecture/adr/ADR-023-labor-step-followups.md
-- decision 5, docs/domain/worker-calendar.md LB23-LB24), beside the minutes its runs record on each step (LB18). Sent whole.
CREATE TABLE work_order_step_plan (
    plan_id varchar(50) PRIMARY KEY,
    work_order_id varchar(50) NOT NULL REFERENCES work_order(work_order_id),
    node_id varchar(50) NOT NULL,
    node_name varchar(100) NOT NULL,
    sort_order integer NOT NULL CHECK (sort_order >= 0),
    planned_minutes integer NOT NULL CHECK (planned_minutes BETWEEN 1 AND 100000),
    updated_by varchar(50) NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_work_order_step_plan UNIQUE (work_order_id, node_id)
);
