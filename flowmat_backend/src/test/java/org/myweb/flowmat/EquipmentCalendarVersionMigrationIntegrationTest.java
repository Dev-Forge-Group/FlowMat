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

/** V94 upgrades disposable V93 schemas; no developer database or business BOM fixtures are used. */
class EquipmentCalendarVersionMigrationIntegrationTest extends IntegrationTestSupport {

    @Test
    void upgradePreservesAllShiftsAndCreatesOneInitialVersionOnlyForEquipmentWithACalendar() {
        String schema = schema();
        JdbcTemplate jdbc = jdbc(schema);
        try {
            flyway(schema, "93").migrate();
            equipment(jdbc, "press", "N");
            equipment(jdbc, "deleted", "Y");
            equipment(jdbc, "always", "N");
            shift(jdbc, "early", "press", "06:00", "14:00", "owner", "2026-10-01T12:34:56.123456Z");
            shift(jdbc, "night", "press", "22:00", "06:00", "editor", "2026-10-02T11:22:33.654321Z");
            shift(jdbc, "old", "deleted", "09:00", "17:00", "owner", "2026-10-01T10:00:00Z");
            List<String> before = rows(jdbc);

            Flyway after = flyway(schema, "94");
            assertEquals(1, after.migrate().migrationsExecuted);
            assertEquals(before, rows(jdbc), "Every old shift value and audit timestamp must survive unchanged");
            assertEquals(2, count(jdbc, "select count(*) from equipment_schedule"));
            assertEquals(2, count(jdbc, "select count(*) from equipment_schedule where effective_from is null"));
            assertEquals(0, count(jdbc, "select count(*) from equipment_schedule where equipment_id='always'"));
            assertEquals(0, count(jdbc, "select count(*) from equipment_calendar c left join equipment_schedule s "
                + "on c.schedule_id=s.schedule_id and c.equipment_id=s.equipment_id where s.schedule_id is null"));
            assertEquals(1, count(jdbc, "select count(distinct schedule_id) from equipment_calendar where equipment_id='press'"));
            assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("update equipment_calendar set schedule_id=null where shift_id='early'"));
            assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("update equipment_calendar set schedule_id='missing' where shift_id='early'"));
            jdbc.update("insert into equipment_schedule values('future','press','2041-09-09','owner',now())");
            assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("insert into equipment_schedule values('duplicate','press','2041-09-09','owner',now())"));
            assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("insert into equipment_schedule values('duplicate_initial','press',null,'owner',now())"));
            assertEquals(0, after.migrate().migrationsExecuted);
            assertEquals(0, count(jdbc, "select count(*) from bom_header"));
            assertEquals(0, count(jdbc, "select count(*) from bom_line"));
        } finally {
            drop(schema);
        }
    }

    @Test
    void emptyCalendarUpgradeDoesNotInventAnyVersion() {
        String schema = schema();
        try {
            flyway(schema, "93").migrate();
            assertEquals(1, flyway(schema, "94").migrate().migrationsExecuted);
            assertEquals(0, count(jdbc(schema), "select count(*) from equipment_schedule"));
        } finally {
            drop(schema);
        }
    }

    @Test
    void theActualV94ScriptCanBeRehearsedAndRolledBackWithOldRowsIntact() throws Exception {
        String schema = schema();
        JdbcTemplate jdbc = jdbc(schema);
        try {
            flyway(schema, "93").migrate();
            equipment(jdbc, "rollback_press", "N");
            shift(jdbc, "rollback_night", "rollback_press", "22:00", "06:00", "owner", "2026-10-01T12:34:56.123456Z");
            List<String> before = rows(jdbc);
            try (Connection connection = source(schema).getConnection()) {
                connection.setAutoCommit(false);
                try {
                    ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V94__equipment_calendar_versions.sql"));
                    try (Statement statement = connection.createStatement(); var result = statement.executeQuery(
                            "select count(*) from equipment_calendar c join equipment_schedule s "
                            + "on c.schedule_id=s.schedule_id and c.equipment_id=s.equipment_id")) {
                        result.next();
                        assertEquals(1, result.getInt(1));
                    }
                } finally {
                    connection.rollback();
                }
            }
            assertEquals(before, rows(jdbc));
            assertEquals(0, count(jdbc, "select count(*) from information_schema.tables where table_schema='" + schema
                + "' and table_name='equipment_schedule'"));
            assertEquals(0, count(jdbc, "select count(*) from information_schema.columns where table_schema='" + schema
                + "' and table_name='equipment_calendar' and column_name='schedule_id'"));
            assertEquals("93", flyway(schema, "93").info().current().getVersion().getVersion());
        } finally {
            drop(schema);
        }
    }

    private void equipment(JdbcTemplate jdbc, String id, String deleted) {
        jdbc.update("insert into equipment(equipment_id,project_id,equipment_name,equipment_type,deleted_yn) "
            + "values(?,'prj_demo_main',?,'machine',?)", id, id, deleted);
    }

    private void shift(JdbcTemplate jdbc, String id, String equipment, String start, String end, String actor, String at) {
        jdbc.update("insert into equipment_calendar(shift_id,equipment_id,shift_start,shift_end,work_days,updated_by,updated_at) "
            + "values(?,?,?::time,?::time,'1,3,5',?,?::timestamptz)", id, equipment, start, end, actor, at);
    }

    private List<String> rows(JdbcTemplate jdbc) {
        return jdbc.queryForList("select (to_jsonb(c)-'schedule_id')::text from equipment_calendar c order by shift_id", String.class);
    }

    private int count(JdbcTemplate jdbc, String sql) { return jdbc.queryForObject(sql, Integer.class); }

    private String schema() { return "equipment_upgrade_" + UUID.randomUUID().toString().replace("-", ""); }

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
