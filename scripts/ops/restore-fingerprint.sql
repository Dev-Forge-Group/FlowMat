-- What must be the same before and after a backup is restored (docs/operations/backup-restore.md).
-- Prints plain lines, so two runs can be compared with diff. Reads only.
\set ON_ERROR_STOP on
\pset format unaligned
\pset tuples_only on
SET TimeZone = 'UTC';
SET extra_float_digits = 3;

-- Migrations: how many were applied, the last one and every checksum.
SELECT 'flyway|' || count(*) || '|' || max(version::int) || '|' || coalesce(sum(checksum::bigint), 0)
  FROM flyway_schema_history WHERE success AND version IS NOT NULL;

-- Every table and its rows.
SELECT 'rows|' || table_name || '|'
       || (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', table_schema, table_name), false, true, '')))[1]::text
  FROM information_schema.tables
 WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
 ORDER BY table_name;

-- The whole content of the tables the two journeys write (docs/status/MONTH-PLAN-2026-11-16.md weeks 1-2).
SELECT 'content|' || name || '|' || hash
  FROM (VALUES
    ('inventory', (SELECT md5(coalesce(string_agg(t::text, E'\n' ORDER BY t::text), '')) FROM inventory t)),
    ('inventory_transaction', (SELECT md5(coalesce(string_agg(t::text, E'\n' ORDER BY t::text), '')) FROM inventory_transaction t)),
    ('lot_master', (SELECT md5(coalesce(string_agg(t::text, E'\n' ORDER BY t::text), '')) FROM lot_master t)),
    ('lot_trace', (SELECT md5(coalesce(string_agg(t::text, E'\n' ORDER BY t::text), '')) FROM lot_trace t)),
    ('purchase_order', (SELECT md5(coalesce(string_agg(t::text, E'\n' ORDER BY t::text), '')) FROM purchase_order t)),
    ('purchase_order_line', (SELECT md5(coalesce(string_agg(t::text, E'\n' ORDER BY t::text), '')) FROM purchase_order_line t)),
    ('purchase_receipt', (SELECT md5(coalesce(string_agg(t::text, E'\n' ORDER BY t::text), '')) FROM purchase_receipt t)),
    ('purchase_return', (SELECT md5(coalesce(string_agg(t::text, E'\n' ORDER BY t::text), '')) FROM purchase_return t)),
    ('warehouse_task', (SELECT md5(coalesce(string_agg(t::text, E'\n' ORDER BY t::text), '')) FROM warehouse_task t)),
    ('work_order', (SELECT md5(coalesce(string_agg(t::text, E'\n' ORDER BY t::text), '')) FROM work_order t)),
    ('stock_allocation', (SELECT md5(coalesce(string_agg(t::text, E'\n' ORDER BY t::text), '')) FROM stock_allocation t)),
    ('production_run', (SELECT md5(coalesce(string_agg(t::text, E'\n' ORDER BY t::text), '')) FROM production_run t)),
    ('production_run_item', (SELECT md5(coalesce(string_agg(t::text, E'\n' ORDER BY t::text), '')) FROM production_run_item t)),
    ('quality_inspection', (SELECT md5(coalesce(string_agg(t::text, E'\n' ORDER BY t::text), '')) FROM quality_inspection t)),
    ('nonconformity', (SELECT md5(coalesce(string_agg(t::text, E'\n' ORDER BY t::text), '')) FROM nonconformity t))
  ) AS content(name, hash)
 ORDER BY name;

-- Per project: stock on hand and reserved, the ledger that explains it, and what was bought.
SELECT 'project|' || p.project_id || '|stock=' || coalesce(s.quantity, 0) || '|reserved=' || coalesce(s.reserved, 0)
       || '|ledger=' || coalesce(l.delta, 0) || '|movements=' || coalesce(l.movements, 0)
       || '|bought=' || coalesce(b.amount, 0)
  FROM project p
  LEFT JOIN (SELECT project_id, sum(quantity) AS quantity, sum(reserved_quantity) AS reserved FROM inventory GROUP BY project_id) s
    USING (project_id)
  LEFT JOIN (SELECT project_id, sum(quantity_delta) AS delta, count(*) AS movements FROM inventory_transaction GROUP BY project_id) l
    USING (project_id)
  LEFT JOIN (SELECT o.project_id, sum(li.quantity * li.unit_price) AS amount
               FROM purchase_order_line li JOIN purchase_order o USING (purchase_order_id) GROUP BY o.project_id) b
    USING (project_id)
 ORDER BY p.project_id;
