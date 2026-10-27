package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** V122-V124 rehearsal and upgrade use a private Testcontainers schema with synthetic, BOM-free legacy rows. */
class PurchasingPlanMigrationRegressionIntegrationTest extends IntegrationTestSupport {
    private static final Instant APPROVED_AT = Instant.parse("2026-10-01T12:34:56.123456Z");
    private static final List<String> NEW_TABLES = List.of(
        "purchase_approval_step", "purchase_order_approval", "purchase_credit_change", "work_order_step_plan");
    private static final List<String> MIGRATIONS = List.of(
        "V122__purchase_approval_step.sql", "V123__purchase_credit_change.sql", "V124__work_order_step_plan.sql");

    @Test
    void legacyApprovalSurvivesTheRollbackRehearsalAndIsMigratedOnceOnTheRealUpgrade() throws Exception {
        String schema = "purchasing_plan_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate jdbc = jdbc(schema);
        try {
            flyway(schema, "121").migrate();
            seed(jdbc);
            Map<String, List<String>> before = legacyRows(jdbc);
            List<String> history = historyRows(jdbc);
            assertNoBom(jdbc);

            try (Connection connection = source(schema).getConnection()) {
                connection.setAutoCommit(false);
                try {
                    for (String migration : MIGRATIONS) {
                        ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/" + migration));
                    }
                    // Every assertion below uses the same uncommitted connection as the three actual SQL scripts.
                    JdbcTemplate rehearsed = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                    assertUpgraded(rehearsed, schema, before);
                    assertEquals(history, historyRows(rehearsed), "A manual SQL rehearsal must not edit Flyway history.");
                } finally {
                    connection.rollback();
                }
            }

            assertEquals(before, legacyRows(jdbc));
            assertEquals(history, historyRows(jdbc));
            for (String table : NEW_TABLES) {
                assertEquals(0, tableCount(jdbc, schema, table), "Rollback must remove " + table + ".");
            }
            assertEquals(0, creditChangeIndexCount(jdbc, schema));
            assertEquals("121", flyway(schema, "121").info().current().getVersion().getVersion());
            assertNoBom(jdbc);

            Flyway upgraded = flyway(schema, "124");
            assertEquals(3, upgraded.migrate().migrationsExecuted);
            assertEquals("124", upgraded.info().current().getVersion().getVersion());
            assertUpgraded(jdbc, schema, before);
            upgraded.validate();
            assertEquals(0, upgraded.migrate().migrationsExecuted);
            assertEquals(0, upgraded.migrate().migrationsExecuted);
            assertUpgraded(jdbc, schema, before);
        } finally {
            drop(schema);
        }
    }

    private void seed(JdbcTemplate jdbc) {
        jdbc.update("insert into project(project_id,project_name,owner_id) values('p','Purchasing upgrade fixture','legacy-approver')");
        jdbc.update("insert into purchase_order(purchase_order_id,project_id,order_number,supplier_name,status,note,created_by,created_at,"
            + "approved_by,approved_at) values('approved-order','p','PO-0001','Original supplier','draft','Keep this note',"
            + "'legacy-author',?,'legacy-approver',?)", Timestamp.from(APPROVED_AT.minusSeconds(3600)), Timestamp.from(APPROVED_AT));
        jdbc.update("insert into purchase_order(purchase_order_id,project_id,order_number,supplier_name,status,created_by,created_at)"
            + " values('unapproved-order','p','PO-0002','Another supplier','draft','legacy-author',?)",
            Timestamp.from(APPROVED_AT.minusSeconds(1800)));
        jdbc.update("insert into work_order(work_order_id,project_id,work_order_number,work_order_title,target_quantity,created_by,created_at)"
            + " values('legacy-work','p','WO-0001','Existing work order',1.2345,'legacy-author',?)",
            Timestamp.from(APPROVED_AT.minusSeconds(7200)));
    }

    private void assertUpgraded(JdbcTemplate jdbc, String schema, Map<String, List<String>> before) {
        assertEquals(before, legacyRows(jdbc), "The upgrade must preserve the complete legacy order rows.");
        for (String table : NEW_TABLES) assertEquals(1, tableCount(jdbc, schema, table));
        assertEquals(1, count(jdbc, "select count(*) from purchase_order_approval"));
        assertEquals("approved-order", jdbc.queryForObject("select purchase_order_id from purchase_order_approval", String.class));
        assertEquals("legacy-approver", jdbc.queryForObject("select approver_id from purchase_order_approval", String.class));
        Timestamp migrated = jdbc.queryForObject("select approved_at from purchase_order_approval", Timestamp.class);
        assertNotNull(migrated);
        assertEquals(APPROVED_AT, migrated.toInstant(), "Approval precision must survive the backfill.");
        assertEquals(0, count(jdbc, "select count(*) from purchase_order_approval where purchase_order_id='unapproved-order'"));
        assertEquals(0, count(jdbc, "select count(*) from purchase_approval_step"));
        assertEquals(0, count(jdbc, "select count(*) from purchase_credit_change"));
        assertEquals(0, count(jdbc, "select count(*) from work_order_step_plan"));
        assertEquals(1, creditChangeIndexCount(jdbc, schema));
        assertNoBom(jdbc);
    }

    private Map<String, List<String>> legacyRows(JdbcTemplate jdbc) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("purchase_order", jdbc.queryForList("select to_jsonb(r)::text from purchase_order r order by purchase_order_id", String.class));
        result.put("work_order", jdbc.queryForList("select to_jsonb(r)::text from work_order r order by work_order_id", String.class));
        return result;
    }

    private List<String> historyRows(JdbcTemplate jdbc) {
        return jdbc.queryForList("select to_jsonb(r)::text from flyway_schema_history r order by installed_rank", String.class);
    }

    private int tableCount(JdbcTemplate jdbc, String schema, String table) {
        return jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=? and table_name=?",
            Integer.class, schema, table);
    }

    private int creditChangeIndexCount(JdbcTemplate jdbc, String schema) {
        return jdbc.queryForObject("select count(*) from pg_indexes where schemaname=? and indexname='ix_purchase_credit_change_order'",
            Integer.class, schema);
    }

    private void assertNoBom(JdbcTemplate jdbc) {
        assertEquals(0, count(jdbc, "select count(*) from bom_header"));
        assertEquals(0, count(jdbc, "select count(*) from bom_line"));
    }

    private int count(JdbcTemplate jdbc, String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private DriverManagerDataSource source(String schema) {
        String url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema;
        return new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private JdbcTemplate jdbc(String schema) {
        return new JdbcTemplate(source(schema));
    }

    private Flyway flyway(String schema, String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target(target).load();
    }

    private void drop(String schema) {
        assertTrue(schema.matches("purchasing_plan_[a-f0-9]{32}"));
        new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()))
            .execute("drop schema if exists " + schema + " cascade");
    }
}
