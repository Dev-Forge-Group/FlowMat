-- What running a node takes (docs/architecture/adr/ADR-043-plan-backwards-from-a-target.md decision 4): equipment,
-- crew, skill, compute or time, per batch of the node or per unit of its first output. Saved on the draft node; a plan
-- reads the copy fixed in the published revision's snapshot. Added only: nothing existing changes.
CREATE TABLE process_requirement (
    process_requirement_id varchar(50) PRIMARY KEY,
    process_id varchar(50) NOT NULL REFERENCES process (process_id),
    sort_order integer NOT NULL CHECK (sort_order >= 0),
    kind varchar(20) NOT NULL CHECK (kind IN ('equipment', 'crew', 'skill', 'compute', 'time')),
    ref varchar(50),
    amount numeric(14, 4) NOT NULL CHECK (amount > 0),
    unit varchar(20),
    per varchar(20) NOT NULL DEFAULT 'batch' CHECK (per IN ('batch', 'output_unit')),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_process_requirement_order UNIQUE (process_id, sort_order)
);
