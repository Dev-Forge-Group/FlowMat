-- V123: Changes to a supplier credit kept for a return, each with a reason (docs/architecture/adr/ADR-027-purchasing.md decision 22,
-- docs/domain/purchasing.md PO52). The credit row stays as first kept; the latest change stands, and no amount means withdrawn.
CREATE TABLE purchase_credit_change (
    change_id varchar(50) PRIMARY KEY,
    return_id varchar(50) NOT NULL REFERENCES purchase_credit(return_id),
    purchase_order_id varchar(50) NOT NULL REFERENCES purchase_order(purchase_order_id),
    amount numeric(24,4) CHECK (amount IS NULL OR amount >= 0),
    credit_note varchar(100),
    reason varchar(500) NOT NULL,
    changed_by varchar(50) NOT NULL,
    changed_at timestamptz NOT NULL,
    CONSTRAINT ck_purchase_credit_change_note CHECK (amount IS NOT NULL OR credit_note IS NULL)
);
CREATE INDEX ix_purchase_credit_change_order ON purchase_credit_change(purchase_order_id);
