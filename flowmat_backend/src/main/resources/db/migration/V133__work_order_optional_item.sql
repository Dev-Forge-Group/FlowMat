-- V133: The optional BOM materials a work order takes (docs/architecture/adr/ADR-033-bom-optional-materials.md decisions 2-4).
-- An optional line is planned only for an order that picks its item. Items rather than BOM lines, because the order's
-- revision may still be resolved again when it is approved. The rows go with their work order.
CREATE TABLE work_order_optional_item (
    work_order_optional_item_id varchar(50) PRIMARY KEY,
    work_order_id varchar(50) NOT NULL REFERENCES work_order(work_order_id) ON DELETE CASCADE,
    item_id varchar(50) NOT NULL,
    created_by varchar(50),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_work_order_optional_item UNIQUE (work_order_id, item_id)
);
