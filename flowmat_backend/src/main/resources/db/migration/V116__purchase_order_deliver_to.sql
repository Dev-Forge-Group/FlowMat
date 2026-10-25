-- V116: Where a purchase order is to arrive (docs/architecture/adr/ADR-027-purchasing.md decision 15, docs/domain/purchasing.md
-- PO39-PO40). A listed place when the project lists any; receipts may still go elsewhere.
ALTER TABLE purchase_order ADD COLUMN deliver_to varchar(100);
