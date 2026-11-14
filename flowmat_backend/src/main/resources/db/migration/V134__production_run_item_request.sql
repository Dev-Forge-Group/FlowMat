-- V134: The request key of a run recording (docs/domain/production-run-recording.md RR1-RR4). A browser that did not hear
-- back sends the same recording again under the same key, and the server answers with what it already recorded instead of
-- taking or making the stock twice. A split over LOTs records one row per LOT under one key, numbered by request_part.
-- Rows recorded before this, and recordings sent without a key, have neither.
ALTER TABLE production_run_item ADD COLUMN request_id varchar(100);
ALTER TABLE production_run_item ADD COLUMN request_part smallint;
ALTER TABLE production_run_item ADD CONSTRAINT ck_production_run_item_request
    CHECK ((request_id IS NULL) = (request_part IS NULL) AND (request_part IS NULL OR request_part >= 0));
CREATE UNIQUE INDEX uq_production_run_item_request
    ON production_run_item (production_run_id, request_id, request_part)
    WHERE request_id IS NOT NULL;
