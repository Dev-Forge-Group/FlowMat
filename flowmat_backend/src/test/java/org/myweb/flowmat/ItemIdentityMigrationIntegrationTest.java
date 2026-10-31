package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Exercises V125 only in owned Testcontainers schemas; no developer database is accessed. */
class ItemIdentityMigrationIntegrationTest extends IntegrationTestSupport {

    private static final String MIGRATION = "db/migration/V125__item_active_identity.sql";
    private static final String CODE_CONFLICT = "Active item codes are duplicated. Resolve duplicates before applying V125.";
    private static final String BARCODE_CONFLICT = "Active item barcodes are duplicated. Resolve duplicates before applying V125.";

    @Test
    void upgradePreservesLegacyRowsAndRollbackRehearsalLeavesNoSchemaOrHistoryChange() throws Exception {
        String schema = schema();
        JdbcTemplate jdbc = jdbc(schema);
        try {
            flyway(schema, "124").migrate();
            seedCompatibleItems(jdbc);
            List<String> before = items(jdbc);
            List<String> history = history(jdbc);
            assertNoBom(jdbc);

            try (Connection connection = source(schema).getConnection()) {
                connection.setAutoCommit(false);
                try {
                    String sql;
                    try (var stream = new ClassPathResource(MIGRATION).getInputStream()) {
                        sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    try (var statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                    JdbcTemplate rehearsed = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                    assertThat(indexCount(rehearsed, schema)).isEqualTo(2);
                    assertThat(items(rehearsed)).isEqualTo(before);
                    assertThat(history(rehearsed)).isEqualTo(history);
                } finally {
                    connection.rollback();
                }
            }

            assertThat(indexCount(jdbc, schema)).isZero();
            assertThat(items(jdbc)).isEqualTo(before);
            assertThat(history(jdbc)).isEqualTo(history);
            Flyway upgraded = flyway(schema, "125");
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(indexCount(jdbc, schema)).isEqualTo(2);
            assertThat(items(jdbc)).isEqualTo(before);
            upgraded.validate();
            assertThat(upgraded.migrate().migrationsExecuted).isZero();
            assertThat(upgraded.migrate().migrationsExecuted).isZero();
            assertThat(items(jdbc)).isEqualTo(before);
            assertNoBom(jdbc);
        } finally {
            drop(schema);
        }
    }

    @Test
    void preexistingCodeDuplicatesAbortWithNoDataHistoryOrIndexChangesAndCanBeRetried() {
        String schema = schema();
        JdbcTemplate jdbc = jdbc(schema);
        try {
            flyway(schema, "124").migrate();
            projects(jdbc);
            insert(jdbc, "first", "p", "private-code-sentinel", null, "N");
            insert(jdbc, "second", "p", "private-code-sentinel", null, "N");
            List<String> before = items(jdbc);
            List<String> history = history(jdbc);
            assertThatThrownBy(() -> flyway(schema, "125").migrate())
                .isInstanceOf(FlywayException.class).hasMessageContaining(CODE_CONFLICT)
                .hasMessageNotContaining("private-code-sentinel");
            assertThat(items(jdbc)).isEqualTo(before);
            assertThat(history(jdbc)).isEqualTo(history);
            assertThat(indexCount(jdbc, schema)).isZero();

            // An operator resolves this owned fixture; the migration never edits duplicate data itself.
            jdbc.update("update item set deleted_yn='Y' where item_id='second'");
            assertThat(flyway(schema, "125").migrate().migrationsExecuted).isEqualTo(1);
            assertThat(indexCount(jdbc, schema)).isEqualTo(2);
            assertNoBom(jdbc);
        } finally {
            drop(schema);
        }
    }

    @Test
    void preexistingBarcodeDuplicatesAbortWithNoDataHistoryOrIndexChanges() {
        String schema = schema();
        JdbcTemplate jdbc = jdbc(schema);
        try {
            flyway(schema, "124").migrate();
            projects(jdbc);
            insert(jdbc, "first", "p", "A", "private-barcode-sentinel", "N");
            insert(jdbc, "second", "p", "B", "private-barcode-sentinel", "N");
            List<String> before = items(jdbc);
            List<String> history = history(jdbc);
            assertThatThrownBy(() -> flyway(schema, "125").migrate())
                .isInstanceOf(FlywayException.class).hasMessageContaining(BARCODE_CONFLICT)
                .hasMessageNotContaining("private-barcode-sentinel");
            assertThat(items(jdbc)).isEqualTo(before);
            assertThat(history(jdbc)).isEqualTo(history);
            assertThat(indexCount(jdbc, schema)).isZero();
            assertNoBom(jdbc);
        } finally {
            drop(schema);
        }
    }

    @Test
    void identityRulesApplyToDirectWritesAndPreserveProjectCaseNullAndSoftDeleteSemantics() {
        String schema = schema();
        JdbcTemplate jdbc = jdbc(schema);
        try {
            flyway(schema, "125").migrate();
            seedCompatibleItems(jdbc);
            assertThatThrownBy(() -> insert(jdbc, "code-conflict", "p", "CODE", null, "N"))
                .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insert(jdbc, "barcode-conflict", "p", "UNUSED", "BAR", "N"))
                .isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update("update item set item_status='inactive' where item_id='active'");
            assertThatThrownBy(() -> insert(jdbc, "inactive-conflict", "p", "CODE", null, "N"))
                .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("update item set item_code='CODE' where item_id='null-a'"))
                .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("update item set barcode='BAR' where item_id='null-b'"))
                .isInstanceOf(DataIntegrityViolationException.class);

            jdbc.update("update item set deleted_yn='Y' where item_id='active'");
            insert(jdbc, "reused", "p", "CODE", "BAR", "N");
            assertThatThrownBy(() -> jdbc.update("update item set deleted_yn='N' where item_id='active'"))
                .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(jdbc.queryForObject("select deleted_yn from item where item_id='active'", String.class)).isEqualTo("Y");
            assertNoBom(jdbc);
        } finally {
            drop(schema);
        }
    }

    private void seedCompatibleItems(JdbcTemplate jdbc) {
        projects(jdbc);
        insert(jdbc, "active", "p", "CODE", "BAR", "N");
        insert(jdbc, "deleted", "p", "CODE", "BAR", "Y");
        insert(jdbc, "other-project", "q", "CODE", "BAR", "N");
        insert(jdbc, "different-case", "p", "code", "bar", "N");
        insert(jdbc, "null-a", "p", "NULL-A", null, "N");
        insert(jdbc, "null-b", "p", "NULL-B", null, "N");
        insert(jdbc, "legacy-null-deleted", "p", "CODE", "BAR", null);
    }

    private void projects(JdbcTemplate jdbc) {
        jdbc.update("insert into project(project_id,project_name,owner_id) values ('p','Identity P','owner'),('q','Identity Q','owner')");
    }

    private void insert(JdbcTemplate jdbc, String id, String project, String code, String barcode, String deleted) {
        jdbc.update("insert into item(item_id,project_id,item_code,item_name,barcode,deleted_yn) values(?,?,?,?,?,?)",
            id, project, code, "Synthetic identity fixture", barcode, deleted);
    }

    private List<String> items(JdbcTemplate jdbc) {
        return jdbc.queryForList("select to_jsonb(i)::text from item i order by item_id", String.class);
    }

    private List<String> history(JdbcTemplate jdbc) {
        return jdbc.queryForList("select to_jsonb(h)::text from flyway_schema_history h order by installed_rank", String.class);
    }

    private int indexCount(JdbcTemplate jdbc, String schema) {
        return jdbc.queryForObject("select count(*) from pg_indexes where schemaname=? "
            + "and indexname in ('uq_item_project_code_active','uq_item_project_barcode_active')", Integer.class, schema);
    }

    private void assertNoBom(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("select count(*) from bom_header", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from bom_line", Integer.class)).isZero();
    }

    private String schema() {
        return "item_identity_" + UUID.randomUUID().toString().replace("-", "");
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
        assertThat(schema).matches("item_identity_[a-f0-9]{32}");
        new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()))
            .execute("drop schema if exists " + schema + " cascade");
    }
}
