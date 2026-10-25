-- V111: Stock sent back to the supplier from a purchase order line (docs/architecture/adr/ADR-027-purchasing.md decision 12,
-- docs/domain/purchasing.md PO26-PO28). The stock leaves as a decreasing adjustment that names the return; rows are never changed.
CREATE TABLE purchase_return (
    return_id varchar(50) PRIMARY KEY,
    purchase_order_id varchar(50) NOT NULL REFERENCES purchase_order(purchase_order_id),
    line_id varchar(50) NOT NULL REFERENCES purchase_order_line(line_id),
    request_id uuid NOT NULL,
    quantity numeric(14,4) NOT NULL CHECK (quantity > 0),
    inventory_id varchar(50) NOT NULL,
    location varchar(100),
    lot_no varchar(100),
    reason varchar(500) NOT NULL,
    returned_by varchar(50) NOT NULL,
    returned_at timestamptz NOT NULL,
    CONSTRAINT uq_purchase_return_request UNIQUE (purchase_order_id, request_id)
);
CREATE INDEX ix_purchase_return_line ON purchase_return(line_id);
