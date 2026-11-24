-- How much each project used FlowMat, week by week (docs/operations/usage-weekly.md, ROADMAP-2026-11-23.md §6 4 and §7).
-- Counts only: project ids and numbers, no user, name or quantity, so the report can be kept and shared. Reads only.
--   psql -v weeks=8 -f scripts/ops/usage-weekly.sql
\set ON_ERROR_STOP on
\if :{?weeks}
\else
  \set weeks 8
\endif
SET TimeZone = 'UTC';

CREATE TEMP VIEW usage_event AS
WITH raw AS (
  -- Setting up: what a factory enters before it plans.
  SELECT project_id, created_at AS at, 'items' AS kind FROM item
  UNION ALL SELECT project_id, created_at, 'steps' FROM process
  -- Using: planning and running the factory.
  UNION ALL SELECT w.project_id, r.published_at, 'published' FROM workflow_revision r JOIN workflow w USING (workflow_id)
    WHERE r.published_at IS NOT NULL
  UNION ALL SELECT project_id, created_at, 'plans_confirmed' FROM plan_confirmation
  UNION ALL SELECT project_id, created_at, 'work_orders' FROM work_order
  UNION ALL SELECT project_id, created_at, 'production_runs' FROM production_run
  UNION ALL SELECT project_id, started_at, 'flow_runs' FROM flow_run WHERE started_at IS NOT NULL
  UNION ALL SELECT project_id, created_at, 'ledger_lines' FROM inventory_transaction
  UNION ALL SELECT project_id, created_at, 'purchase_orders' FROM purchase_order
)
SELECT raw.project_id, raw.at, raw.kind, to_char(date_trunc('week', raw.at), 'IYYY-"W"IW') AS week,
       -- Use: more than setting up, a day or more after the project was made (a sample is built in seconds).
       raw.kind NOT IN ('items', 'steps') AND raw.at >= p.created_at + interval '1 day' AS use
  FROM raw JOIN project p USING (project_id)
 WHERE raw.at >= date_trunc('week', now()) - make_interval(weeks => :weeks - 1);

\echo 'Week by week'
SELECT week, project_id,
       count(*) FILTER (WHERE kind = 'items') AS items,
       count(*) FILTER (WHERE kind = 'steps') AS steps,
       count(*) FILTER (WHERE kind = 'published') AS published,
       count(*) FILTER (WHERE kind = 'plans_confirmed') AS plans_confirmed,
       count(*) FILTER (WHERE kind = 'work_orders') AS work_orders,
       count(*) FILTER (WHERE kind = 'production_runs') AS production_runs,
       count(*) FILTER (WHERE kind = 'flow_runs') AS flow_runs,
       count(*) FILTER (WHERE kind = 'ledger_lines') AS ledger_lines,
       count(*) FILTER (WHERE kind = 'purchase_orders') AS purchase_orders,
       bool_or(use) AS used
  FROM usage_event
 GROUP BY 1, 2
 ORDER BY 1 DESC, 2;

\echo 'Used in each of the last four weeks (판정 1 counts these)'
SELECT project_id, count(DISTINCT week) AS weeks_used
  FROM usage_event
 WHERE use AND at >= date_trunc('week', now()) - interval '3 weeks'
 GROUP BY 1
HAVING count(DISTINCT week) = 4
 ORDER BY 1;

\echo 'First run: minutes from making the project to its first item, published flow and confirmed plan (novice trial)'
-- How long a new project took to get going (docs/operations/novice-trial.md). Projects made in the window only; no user
-- or name. A sample (홈의 "견본으로 시작") publishes within seconds, so near-zero rows are samples. A plan that is only
-- viewed is not stored, so the trial's "plan shown" is timed by the observer; a confirmed plan is the stored end.
SELECT p.project_id,
       to_char(p.created_at, 'YYYY-MM-DD HH24:MI') AS created_utc,
       round(extract(epoch FROM (SELECT min(i.created_at) FROM item i WHERE i.project_id = p.project_id) - p.created_at) / 60, 1)
         AS minutes_to_first_item,
       round(extract(epoch FROM (SELECT min(r.published_at) FROM workflow_revision r JOIN workflow w USING (workflow_id)
                                  WHERE w.project_id = p.project_id) - p.created_at) / 60, 1) AS minutes_to_first_publish,
       round(extract(epoch FROM (SELECT min(c.created_at) FROM plan_confirmation c WHERE c.project_id = p.project_id)
                                - p.created_at) / 60, 1) AS minutes_to_first_confirmed_plan
  FROM project p
 WHERE p.created_at >= date_trunc('week', now()) - make_interval(weeks => :weeks - 1)
 ORDER BY p.created_at DESC;
