-- V106: Purchase orders and what was received on them (docs/architecture/adr/ADR-027-purchasing.md, docs/domain/purchasing.md).
-- Orders are never deleted: they are cancelled or closed with a reason. A receipt names the stock record it went into.
CREATE TABLE purchase_order (
    purchase_order_id varchar(50) PRIMARY KEY,
    project_id varchar(50) NOT NULL,
    order_number varchar(20) NOT NULL,
    supplier_name varchar(100) NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('draft', 'ordered', 'received', 'closed', 'cancelled')),
    expected_date date,
    note varchar(500),
    created_by varchar(50) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_by varchar(50),
    updated_at timestamptz,
    ordered_by varchar(50),
    ordered_at timestamptz,
    ended_by varchar(50),
    ended_at timestamptz,
    end_reason varchar(500),
    CONSTRAINT uq_purchase_order_number UNIQUE (project_id, order_number),
    CONSTRAINT ck_purchase_order_end CHECK (status NOT IN ('closed', 'cancelled') OR (ended_by IS NOT NULL AND end_reason IS NOT NULL))
);
CREATE INDEX ix_purchase_order_project ON purchase_order(project_id, created_at);

CREATE TABLE purchase_order_line (
    line_id varchar(50) PRIMARY KEY,
    purchase_order_id varchar(50) NOT NULL REFERENCES purchase_order(purchase_order_id),
    line_no integer NOT NULL CHECK (line_no > 0),
    item_id varchar(50) NOT NULL,
    quantity numeric(14,4) NOT NULL CHECK (quantity > 0),
    received_quantity numeric(14,4) NOT NULL DEFAULT 0 CHECK (received_quantity >= 0 AND received_quantity <= quantity),
    expected_date date,
    note varchar(500),
    CONSTRAINT uq_purchase_order_line_no UNIQUE (purchase_order_id, line_no),
    CONSTRAINT uq_purchase_order_line_item UNIQUE (purchase_order_id, item_id)
);

CREATE TABLE purchase_receipt (
    receipt_id varchar(50) PRIMARY KEY,
    purchase_order_id varchar(50) NOT NULL REFERENCES purchase_order(purchase_order_id),
    line_id varchar(50) NOT NULL REFERENCES purchase_order_line(line_id),
    request_id uuid NOT NULL,
    quantity numeric(14,4) NOT NULL CHECK (quantity > 0),
    inventory_id varchar(50) NOT NULL,
    location varchar(100),
    lot_no varchar(100),
    received_by varchar(50) NOT NULL,
    received_at timestamptz NOT NULL
);
CREATE INDEX ix_purchase_receipt_order ON purchase_receipt(purchase_order_id, request_id);
