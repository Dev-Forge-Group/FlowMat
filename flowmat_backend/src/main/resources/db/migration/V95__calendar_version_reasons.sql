-- V95: Why a calendar version was saved (docs/architecture/adr/ADR-017-version-reasons.md, 2026-10-18): an optional reason of at
-- most 200 characters on each worker schedule version and equipment calendar version. Versions saved before have none.
ALTER TABLE worker_schedule ADD COLUMN reason varchar(200);
ALTER TABLE equipment_schedule ADD COLUMN reason varchar(200);
