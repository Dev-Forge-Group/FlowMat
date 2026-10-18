-- V90: How many work orders equipment that runs them side by side takes at once (docs/domain/equipment.md E16-E19).
-- Null keeps V86's meaning: one at a time when concurrent_work_orders_yn is N, no limit when it is Y.
ALTER TABLE equipment
    ADD COLUMN concurrent_work_orders_limit integer,
    ADD CONSTRAINT ck_equipment_concurrent_work_orders_limit CHECK (
        concurrent_work_orders_limit IS NULL
        OR (concurrent_work_orders_yn = 'Y' AND concurrent_work_orders_limit BETWEEN 2 AND 99));
