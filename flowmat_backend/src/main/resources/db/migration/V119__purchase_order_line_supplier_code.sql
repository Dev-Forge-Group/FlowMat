-- V119: The code the supplier uses for a line's item, kept as the order is placed (docs/architecture/adr/ADR-027-purchasing.md
-- decision 17, docs/domain/purchasing.md PO45), so changing the item's usual supplier or code later leaves the order as it was.
ALTER TABLE purchase_order_line ADD COLUMN supplier_item_code varchar(100);
