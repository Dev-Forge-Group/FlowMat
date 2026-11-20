-- Guided views (docs/architecture/adr/ADR-037-guided-views.md): a workflow's chapters for reading, one row each. A
-- chapter names the processes it shows, in the order a reader walks them. A deleted process is left out when the chapter
-- is read, so deleting a process does not touch this table.
CREATE TABLE workflow_guided_view (
    guided_view_id varchar(50) PRIMARY KEY,
    workflow_id varchar(50) NOT NULL REFERENCES workflow(workflow_id),
    project_id varchar(50) NOT NULL,
    sort_order integer NOT NULL CHECK (sort_order >= 0),
    label varchar(48) NOT NULL CHECK (length(btrim(label)) > 0),
    note varchar(140),
    focus_process_ids jsonb NOT NULL CHECK (
        jsonb_typeof(focus_process_ids) = 'array' AND jsonb_array_length(focus_process_ids) BETWEEN 1 AND 30
    ),
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    created_by varchar(50),
    updated_by varchar(50),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_workflow_guided_view_order ON workflow_guided_view (workflow_id, sort_order);
