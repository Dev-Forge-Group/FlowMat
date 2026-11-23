-- How long each factory took to get going (docs/operations/onboarding.md, ROADMAP-2026-11-23.md §5 2): per project, the
-- hours from its creation to each first step that matters, and how many days it was used in its first four weeks.
-- Project ids and numbers only, no user, name or quantity, so the report can be kept and shared. Reads only.
--   psql -v days=120 -f scripts/ops/onboarding.sql
\set ON_ERROR_STOP on
\if :{?days}
\else
  \set days 120
\endif
SET TimeZone = 'UTC';

CREATE TEMP VIEW onboarding_first AS
SELECT p.project_id, p.created_at,
       (SELECT min(i.created_at) FROM item i WHERE i.project_id = p.project_id) AS first_item,
       (SELECT count(*) FROM item i WHERE i.project_id = p.project_id
           AND i.created_at < p.created_at + interval '28 days') AS items_28d,
       (SELECT min(b.approved_at) FROM bom_header b WHERE b.project_id = p.project_id) AS first_bom,
       (SELECT min(t.created_at) FROM inventory_transaction t WHERE t.project_id = p.project_id) AS first_stock,
       (SELECT min(r.published_at) FROM workflow_revision r JOIN workflow w USING (workflow_id)
         WHERE w.project_id = p.project_id) AS first_published,
       (SELECT min(c.created_at) FROM plan_confirmation c WHERE c.project_id = p.project_id) AS first_plan,
       (SELECT min(r.actual_end_at) FROM production_run r WHERE r.project_id = p.project_id
           AND r.run_status = 'finished') AS first_run
  FROM project p
 WHERE p.created_at >= now() - make_interval(days => :days);

\echo 'Hours from the project to each first step (empty: not yet)'
SELECT project_id, to_char(created_at, 'YYYY-MM-DD') AS created,
       round(extract(epoch FROM first_item - created_at) / 3600, 1) AS h_item,
       round(extract(epoch FROM first_bom - created_at) / 3600, 1) AS h_bom,
       round(extract(epoch FROM first_stock - created_at) / 3600, 1) AS h_stock,
       round(extract(epoch FROM first_published - created_at) / 3600, 1) AS h_published,
       round(extract(epoch FROM first_plan - created_at) / 3600, 1) AS h_plan,
       round(extract(epoch FROM first_run - created_at) / 3600, 1) AS h_run,
       -- Moving data in: from the first item to the last of the first BOM, stock and published flow (elapsed, not worked).
       round(extract(epoch FROM greatest(first_bom, first_stock, first_published) - first_item) / 3600, 1) AS h_data_in,
       items_28d,
       -- "Start from a sample" publishes and stocks in seconds; such a project is not a factory's own start.
       coalesce(first_published - created_at < interval '5 minutes', false) AS sample_like
  FROM onboarding_first
 ORDER BY created_at;

\echo 'Days used in the first 28 days (as usage-weekly.sql counts use)'
WITH used AS (
  SELECT w.project_id, r.published_at AS at FROM workflow_revision r JOIN workflow w USING (workflow_id)
   WHERE r.published_at IS NOT NULL
  UNION ALL SELECT project_id, created_at FROM plan_confirmation
  UNION ALL SELECT project_id, created_at FROM work_order
  UNION ALL SELECT project_id, created_at FROM production_run
  UNION ALL SELECT project_id, created_at FROM inventory_transaction
  UNION ALL SELECT project_id, created_at FROM purchase_order
)
SELECT f.project_id,
       count(DISTINCT u.at::date) FILTER (WHERE u.at >= f.created_at + interval '1 day'
           AND u.at < f.created_at + interval '28 days') AS days_used_28d
  FROM onboarding_first f LEFT JOIN used u USING (project_id)
 GROUP BY f.project_id, f.created_at
 ORDER BY f.created_at;
