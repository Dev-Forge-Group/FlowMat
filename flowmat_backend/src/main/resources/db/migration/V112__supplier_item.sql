-- V112: The supplier an item is usually bought from, with that supplier's code for it (docs/architecture/adr/ADR-027-purchasing.md
-- decision 13, docs/domain/purchasing.md PO29-PO31). One per item in a project; drafts of purchase orders go to it.
CREATE TABLE supplier_item (
    supplier_item_id varchar(50) PRIMARY KEY,
    project_id varchar(50) NOT NULL,
    item_id varchar(50) NOT NULL,
    supplier_id varchar(50) NOT NULL REFERENCES supplier(supplier_id),
    supplier_item_code varchar(100),
    version bigint NOT NULL DEFAULT 0,
    updated_by varchar(50) NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_supplier_item UNIQUE (project_id, item_id)
);
CREATE INDEX ix_supplier_item_supplier ON supplier_item(supplier_id);
