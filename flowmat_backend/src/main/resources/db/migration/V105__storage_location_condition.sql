-- V105: How a place keeps stock, as free text like an item's storage condition (docs/domain/storage-location.md SL1-SL4).
-- Places inside without their own take the nearest outer place's. Stock kept differently from its item's condition is
-- pointed out, never refused.
ALTER TABLE storage_location ADD COLUMN storage_condition varchar(100);
