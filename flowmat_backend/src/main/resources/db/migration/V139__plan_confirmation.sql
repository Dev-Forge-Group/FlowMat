-- A confirmed plan (docs/architecture/adr/ADR-043-plan-backwards-from-a-target.md decision 7): which draft work order and
-- draft purchase order one confirmation made, so the same requestId answers the same again. Added only.
CREATE TABLE plan_confirmation (
    workflow_id varchar(50) NOT NULL,
    request_id varchar(100) NOT NULL,
    project_id varchar(50) NOT NULL,
    revision_id varchar(50) NOT NULL,
    target_port_id varchar(50),
    target_connection_id varchar(50),
    target_quantity numeric(18, 4) NOT NULL,
    work_order_id varchar(50) NOT NULL,
    purchase_order_id varchar(50),
    created_by varchar(50),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_plan_confirmation PRIMARY KEY (workflow_id, request_id)
);
