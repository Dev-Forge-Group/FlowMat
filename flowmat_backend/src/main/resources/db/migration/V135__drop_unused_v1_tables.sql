-- V135: Drops the twelve tables V1 created that nothing ever used (docs/architecture/domain-map.md, modules.yml
-- unowned_tables until 2026-11-14). No entity maps them, no code reads or writes them, and no foreign key points at or out
-- of them. They were empty wherever they were looked at; a database where one holds rows is not what this was decided for,
-- so the migration stops there instead of deleting data, and that environment decides what the rows are first.
DO $$
DECLARE
    unused text;
    held bigint;
BEGIN
    FOREACH unused IN ARRAY ARRAY[
        'cad_import_job', 'payment_discount', 'project_activity_log', 'project_comment', 'project_export', 'project_file',
        'project_permission', 'resource_types', 'resource_usage', 'resources', 'simulation_run', 'simulation_step'
    ] LOOP
        EXECUTE format('SELECT count(*) FROM %I', unused) INTO held;
        IF held > 0 THEN
            RAISE EXCEPTION 'Table % holds % row(s). V135 drops it only when it is empty: decide what the rows are first.', unused, held;
        END IF;
    END LOOP;
END $$;

-- Without CASCADE: anything that came to depend on one of them stops the migration as well.
DROP TABLE cad_import_job;
DROP TABLE payment_discount;
DROP TABLE project_activity_log;
DROP TABLE project_comment;
DROP TABLE project_export;
DROP TABLE project_file;
DROP TABLE project_permission;
DROP TABLE resource_usage;
DROP TABLE resources;
DROP TABLE resource_types;
DROP TABLE simulation_step;
DROP TABLE simulation_run;
