-- V88: Where a Join step's input came from (docs/architecture/adr/ADR-007-join-gateway.md decision 8). A Join waits for
-- several branches, so one flow_run_step.source_step_id cannot say it: each arrival is a row, numbered in arrival order,
-- naming the step and connection it came by. Other steps keep their one source as before (V40).
CREATE TABLE flow_run_step_source (
    step_source_id varchar(50) PRIMARY KEY,
    flow_run_id varchar(50) NOT NULL,
    step_id varchar(50) NOT NULL,
    arrival_no integer NOT NULL CHECK (arrival_no >= 1),
    source_step_id varchar(50) NOT NULL,
    connection_id varchar(50) NOT NULL,
    arrived_at timestamptz NOT NULL,
    CONSTRAINT fk_flow_run_step_source_step FOREIGN KEY (step_id, flow_run_id)
        REFERENCES flow_run_step (step_id, flow_run_id),
    CONSTRAINT fk_flow_run_step_source_from FOREIGN KEY (source_step_id, flow_run_id)
        REFERENCES flow_run_step (step_id, flow_run_id),
    CONSTRAINT uq_flow_run_step_source_arrival UNIQUE (step_id, arrival_no),
    CONSTRAINT uq_flow_run_step_source_once UNIQUE (step_id, source_step_id, connection_id)
);
CREATE INDEX idx_flow_run_step_source_run ON flow_run_step_source (flow_run_id);
