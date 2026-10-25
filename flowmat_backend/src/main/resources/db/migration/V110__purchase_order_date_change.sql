-- V110: Expected dates moved on purchase orders being received, with the reason (docs/architecture/adr/ADR-027-purchasing.md
-- decision 11, docs/domain/purchasing.md PO22-PO23). A row without a line is the order's own expected date; rows are never changed.
CREATE TABLE purchase_order_date_change (
    change_id varchar(50) PRIMARY KEY,
    purchase_order_id varchar(50) NOT NULL REFERENCES purchase_order(purchase_order_id),
    line_id varchar(50) REFERENCES purchase_order_line(line_id),
    previous_date date,
    expected_date date,
    reason varchar(500) NOT NULL,
    changed_by varchar(50) NOT NULL,
    changed_at timestamptz NOT NULL,
    CONSTRAINT ck_purchase_order_date_change CHECK (previous_date IS DISTINCT FROM expected_date)
);
CREATE INDEX ix_purchase_order_date_change_order ON purchase_order_date_change(purchase_order_id, changed_at);
