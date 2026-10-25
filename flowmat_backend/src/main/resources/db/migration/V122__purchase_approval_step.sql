-- V122: Approval steps by order value besides the owner's limit, and who approved each draft (docs/architecture/adr/ADR-027-purchasing.md
-- decision 21, docs/domain/purchasing.md PO50-PO51). Approvals already given are kept as given by whoever gave them.
CREATE TABLE purchase_approval_step (
    step_id varchar(50) PRIMARY KEY,
    project_id varchar(50) NOT NULL REFERENCES purchase_setting(project_id),
    step_no integer NOT NULL CHECK (step_no BETWEEN 1 AND 5),
    from_value numeric(14,4) NOT NULL CHECK (from_value >= 0),
    approver_id varchar(50) NOT NULL,
    CONSTRAINT uq_purchase_approval_step_no UNIQUE (project_id, step_no),
    CONSTRAINT uq_purchase_approval_step_approver UNIQUE (project_id, approver_id)
);
CREATE TABLE purchase_order_approval (
    approval_id varchar(50) PRIMARY KEY,
    purchase_order_id varchar(50) NOT NULL REFERENCES purchase_order(purchase_order_id),
    approver_id varchar(50) NOT NULL,
    approved_at timestamptz NOT NULL,
    CONSTRAINT uq_purchase_order_approval UNIQUE (purchase_order_id, approver_id)
);
INSERT INTO purchase_order_approval (approval_id, purchase_order_id, approver_id, approved_at)
SELECT md5('v122|' || purchase_order_id), purchase_order_id, approved_by, approved_at
FROM purchase_order WHERE approved_at IS NOT NULL AND approved_by IS NOT NULL;
