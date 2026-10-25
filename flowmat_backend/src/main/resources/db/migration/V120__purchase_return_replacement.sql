-- V120: Stock sent back for a replacement comes again on the same purchase order line (docs/architecture/adr/ADR-027-purchasing.md
-- decision 18, docs/domain/purchasing.md PO46). A line then receives what it ordered and what went back to be replaced.
ALTER TABLE purchase_return ADD COLUMN replacement_yn char(1) NOT NULL DEFAULT 'N' CHECK (replacement_yn IN ('Y', 'N'));
ALTER TABLE purchase_order_line ADD COLUMN replacement_quantity numeric(14,4) NOT NULL DEFAULT 0;
-- V106's check on received_quantity, named by PostgreSQL as it reads two columns.
ALTER TABLE purchase_order_line DROP CONSTRAINT purchase_order_line_check;
ALTER TABLE purchase_order_line ADD CONSTRAINT ck_purchase_order_line_received CHECK (received_quantity >= 0
    AND replacement_quantity >= 0 AND replacement_quantity <= received_quantity AND received_quantity <= quantity + replacement_quantity);
