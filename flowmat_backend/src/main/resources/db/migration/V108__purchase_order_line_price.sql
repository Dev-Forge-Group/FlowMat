-- V108: The agreed price per stock unit of a purchase order line, optional (docs/architecture/adr/ADR-027-purchasing.md
-- decision 9, docs/domain/purchasing.md PO14). It is shown with the order and never changes the item's unit cost.
ALTER TABLE purchase_order_line ADD COLUMN unit_price numeric(14,4) CHECK (unit_price IS NULL OR unit_price >= 0);
