-- V93: Versions of workers' weekly shifts and rotas (docs/architecture/adr/ADR-013-rota-history.md, user decision
-- 2026-10-18). A version applies from effective_from (NULL: from the start) until the next one starts, so changing a
-- worker's shifts leaves earlier dates as they were. What each worker has now becomes their version from the start.
CREATE TABLE worker_schedule (
    schedule_id varchar(50) PRIMARY KEY,
    project_id varchar(50) NOT NULL REFERENCES project(project_id),
    user_id varchar(50) NOT NULL,
    effective_from date,
    updated_by varchar(50) NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE UNIQUE INDEX uq_worker_schedule_start ON worker_schedule (project_id, user_id, COALESCE(effective_from, DATE '0001-01-01'));

INSERT INTO worker_schedule (schedule_id, project_id, user_id, effective_from, updated_by, updated_at)
SELECT 'wsc_' || md5(project_id || ':' || user_id), project_id, user_id, NULL, max(updated_by), max(updated_at)
FROM (SELECT project_id, user_id, updated_by, updated_at FROM worker_shift
      UNION ALL
      SELECT project_id, user_id, updated_by, updated_at FROM worker_rota) existing
GROUP BY project_id, user_id;

ALTER TABLE worker_shift ADD COLUMN schedule_id varchar(50) REFERENCES worker_schedule(schedule_id);
UPDATE worker_shift SET schedule_id = 'wsc_' || md5(project_id || ':' || user_id);
ALTER TABLE worker_shift ALTER COLUMN schedule_id SET NOT NULL;
CREATE INDEX ix_worker_shift_schedule ON worker_shift(schedule_id);

ALTER TABLE worker_rota ADD COLUMN schedule_id varchar(50) REFERENCES worker_schedule(schedule_id);
UPDATE worker_rota SET schedule_id = 'wsc_' || md5(project_id || ':' || user_id);
ALTER TABLE worker_rota ALTER COLUMN schedule_id SET NOT NULL;
ALTER TABLE worker_rota DROP CONSTRAINT uq_worker_rota_worker;
ALTER TABLE worker_rota ADD CONSTRAINT uq_worker_rota_schedule UNIQUE (schedule_id);
