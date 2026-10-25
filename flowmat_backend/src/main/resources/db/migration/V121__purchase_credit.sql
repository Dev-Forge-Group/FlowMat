-- V121: What a supplier credited for stock sent back (docs/architecture/adr/ADR-027-purchasing.md decision 19,
-- docs/domain/purchasing.md PO47). One row per return, never changed; the return itself stays as it was.
CREATE TABLE purchase_credit (
    return_id varchar(50) PRIMARY KEY REFERENCES purchase_return(return_id),
    purchase_order_id varchar(50) NOT NULL REFERENCES purchase_order(purchase_order_id),
    amount numeric(24,4) NOT NULL CHECK (amount >= 0),
    credit_note varchar(100),
    credited_by varchar(50) NOT NULL,
    credited_at timestamptz NOT NULL
);
CREATE INDEX ix_purchase_credit_order ON purchase_credit(purchase_order_id);
