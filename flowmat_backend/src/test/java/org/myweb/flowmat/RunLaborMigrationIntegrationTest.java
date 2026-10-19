package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Upgrades disposable V95 schemas and rehearses V96 transaction rollback; no developer database is used. */
class RunLaborMigrationIntegrationTest extends IntegrationTestSupport {
    @Test
    void upgradePreservesExistingRunsAndEnforcesIdempotencyReferenceAndAuditConstraints() {
        String schema = schema();
        JdbcTemplate jdbc = jdbc(schema);
        try {
            flyway(schema, "95").migrate();
            run(jdbc, "old-actual", "actual");
            run(jdbc, "old-simulation", "simulation");
            List<String> before = rows(jdbc);

            Flyway upgraded = flyway(schema, "96");
            assertEquals(1, upgraded.migrate().migrationsExecuted);
            assertEquals(before, rows(jdbc));
            assertEquals(0, count(jdbc, "select count(*) from production_run_labor"));
            UUID key = UUID.randomUUID();
            labor(jdbc, "first", "old-actual", key, 1);
            labor(jdbc, "other-run", "old-simulation", key, 10080);
            assertThrows(DataIntegrityViolationException.class, () -> labor(jdbc, "duplicate", "old-actual", key, 1));
            assertThrows(DataIntegrityViolationException.class, () -> labor(jdbc, "missing-run", "absent", UUID.randomUUID(), 1));
            assertThrows(DataIntegrityViolationException.class, () -> labor(jdbc, "zero", "old-actual", UUID.randomUUID(), 0));
            assertThrows(DataIntegrityViolationException.class, () -> labor(jdbc, "too-long", "old-actual", UUID.randomUUID(), 10081));
            assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("update production_run_labor set cancelled_yn='Y' where run_labor_id='first'"));
            assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("update production_run_labor set cancel_reason='unexplained' where run_labor_id='first'"));
            assertEquals(1, jdbc.update("update production_run_labor set cancelled_yn='Y',cancelled_by='demo-owner',"
                + "cancelled_at='2026-10-18T01:02:03.123456Z',cancel_reason='Wrong duration' where run_labor_id='first'"));
            assertEquals(2, count(jdbc, "select count(*) from production_run_labor"));
            assertEquals(1, count(jdbc, "select count(*) from production_run_labor where cancelled_yn='Y'"));
            assertEquals(before, rows(jdbc), "Recording or cancelling labor must not rewrite legacy run cost or timestamps");
            assertEquals(0, upgraded.migrate().migrationsExecuted);
            assertEquals(0, count(jdbc, "select count(*) from bom_header"));
            assertEquals(0, count(jdbc, "select count(*) from bom_line"));
        } finally {
            drop(schema);
        }
    }

    @Test
    void theActualV96SqlRollsBackWithoutLeavingTableIndexOrHistoryChanges() throws Exception {
        String schema = schema();
        JdbcTemplate jdbc = jdbc(schema);
        try {
            flyway(schema, "95").migrate();
            run(jdbc, "rollback-run", "actual");
            List<String> before = rows(jdbc);
            try (Connection connection = source(schema).getConnection()) {
                connection.setAutoCommit(false);
                try {
                    ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V96__production_run_labor.sql"));
                    try (Statement statement = connection.createStatement()) {
                        statement.executeUpdate("insert into production_run_labor(run_labor_id,production_run_id,project_id,user_id,"
                            + "worked_minutes,request_id,recorded_by,recorded_at) values('rollback-labor','rollback-run',"
                            + "'prj_demo_main','demo-owner',15,'00000000-0000-0000-0000-000000000001','demo-owner',now())");
                        try (var result = statement.executeQuery("select count(*) from production_run_labor")) {
                            result.next();
                            assertEquals(1, result.getInt(1));
                        }
                    }
                } finally {
                    connection.rollback();
                }
            }
            assertEquals(before, rows(jdbc));
            assertEquals(0, count(jdbc, "select count(*) from information_schema.tables where table_schema='" + schema
                + "' and table_name='production_run_labor'"));
            assertEquals(0, count(jdbc, "select count(*) from pg_indexes where schemaname='" + schema
                + "' and indexname='ix_production_run_labor_run'"));
            assertEquals("95", flyway(schema, "95").info().current().getVersion().getVersion());
        } finally {
            drop(schema);
        }
    }

    @Test
    void anEmptyV95SchemaUpgradesWithoutInventingWorkedTime() {
        String schema = schema();
        try {
            flyway(schema, "95").migrate();
            assertEquals(1, flyway(schema, "96").migrate().migrationsExecuted);
            assertEquals(0, count(jdbc(schema), "select count(*) from production_run_labor"));
        } finally {
            drop(schema);
        }
    }

    private void run(JdbcTemplate jdbc, String id, String type) {
        jdbc.update("insert into production_run(production_run_id,project_id,run_number,run_type,run_status,planned_output_qty,"
            + "actual_end_at,total_material_cost,created_by,created_at) values(?,'prj_demo_main',?,?,'completed',1.2345,"
            + "'2026-10-01T12:34:56.123456Z',17.2500,'demo-owner','2026-10-01T09:00:00.654321Z')", id, id, type);
    }

    private void labor(JdbcTemplate jdbc, String id, String run, UUID key, int minutes) {
        jdbc.update("insert into production_run_labor(run_labor_id,production_run_id,project_id,user_id,worked_minutes,"
            + "request_id,recorded_by,recorded_at) values(?,?,'prj_demo_main','demo-owner',?,?,'demo-owner',"
            + "'2026-10-18T00:00:00.123456Z')", id, run, minutes, key);
    }

    private List<String> rows(JdbcTemplate jdbc) {
        return jdbc.queryForList("select to_jsonb(r)::text from production_run r order by production_run_id", String.class);
    }

    private int count(JdbcTemplate jdbc, String sql) { return jdbc.queryForObject(sql, Integer.class); }
    private String schema() { return "labor_upgrade_" + UUID.randomUUID().toString().replace("-", ""); }

    private DriverManagerDataSource source(String schema) {
        String url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema;
        return new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private JdbcTemplate jdbc(String schema) { return new JdbcTemplate(source(schema)); }

    private Flyway flyway(String schema, String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target(target).load();
    }

    private void drop(String schema) {
        new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()))
            .execute("drop schema " + schema + " cascade");
    }
}
