-- V94: Versions of equipment calendars (docs/architecture/adr/ADR-014-equipment-calendar-history.md, user decision
-- 2026-10-18). A version applies from effective_from (NULL: from the start) until the next one starts; one without shifts
-- runs around the clock. Changing or removing a calendar leaves earlier dates as they were. What each piece of equipment
-- has now becomes its version from the start; equipment without a calendar gets none and stays around the clock.
CREATE TABLE equipment_schedule (
    schedule_id varchar(50) PRIMARY KEY,
    equipment_id varchar(50) NOT NULL REFERENCES equipment(equipment_id),
    effective_from date,
    updated_by varchar(50) NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE UNIQUE INDEX uq_equipment_schedule_start ON equipment_schedule (equipment_id, COALESCE(effective_from, DATE '0001-01-01'));

INSERT INTO equipment_schedule (schedule_id, equipment_id, effective_from, updated_by, updated_at)
SELECT 'esc_' || md5(equipment_id), equipment_id, NULL, max(updated_by), max(updated_at)
FROM equipment_calendar
GROUP BY equipment_id;

ALTER TABLE equipment_calendar ADD COLUMN schedule_id varchar(50) REFERENCES equipment_schedule(schedule_id);
UPDATE equipment_calendar SET schedule_id = 'esc_' || md5(equipment_id);
ALTER TABLE equipment_calendar ALTER COLUMN schedule_id SET NOT NULL;
CREATE INDEX ix_equipment_calendar_schedule ON equipment_calendar(schedule_id);
