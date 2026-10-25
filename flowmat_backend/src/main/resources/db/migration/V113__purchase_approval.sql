-- V113: The project owner's approval of purchase orders from a limit up (docs/architecture/adr/ADR-027-purchasing.md decision 14,
-- docs/domain/purchasing.md PO33-PO35). Without a limit nothing needs approval.
CREATE TABLE purchase_setting (
    project_id varchar(50) PRIMARY KEY,
    approval_limit numeric(14,4) CHECK (approval_limit IS NULL OR approval_limit >= 0),
    version bigint NOT NULL DEFAULT 0,
    updated_by varchar(50) NOT NULL,
    updated_at timestamptz NOT NULL
);
ALTER TABLE purchase_order ADD COLUMN approved_by varchar(50), ADD COLUMN approved_at timestamptz;
