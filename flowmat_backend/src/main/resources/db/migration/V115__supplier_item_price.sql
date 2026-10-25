-- V115: The usual supplier's own price for an item, per stock unit (docs/domain/purchasing.md PO38). Drafts for that supplier
-- start from it; the item's unit cost does not change by itself.
ALTER TABLE supplier_item ADD COLUMN unit_price numeric(14,4) CHECK (unit_price IS NULL OR unit_price >= 0);
