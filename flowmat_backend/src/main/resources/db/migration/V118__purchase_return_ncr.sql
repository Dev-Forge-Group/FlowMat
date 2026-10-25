-- V118: A purchase return may name the NCR that decided to send the stock back (docs/architecture/adr/ADR-027-purchasing.md
-- decision 16, docs/domain/purchasing.md PO42). The NCR's number is kept with it, as NCR numbers are never reused; no foreign key
-- crosses into quality, as none does into inventory.
ALTER TABLE purchase_return ADD COLUMN nonconformity_id varchar(50);
ALTER TABLE purchase_return ADD COLUMN ncr_no varchar(20);
ALTER TABLE purchase_return ADD CONSTRAINT ck_purchase_return_ncr CHECK ((nonconformity_id IS NULL) = (ncr_no IS NULL));
