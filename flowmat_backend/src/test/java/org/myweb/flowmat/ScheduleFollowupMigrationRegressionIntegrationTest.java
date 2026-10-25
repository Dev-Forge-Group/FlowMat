package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** V95-V101 upgrade synthetic V94 schedules in a private Testcontainers schema; no developer database is used. */
class ScheduleFollowupMigrationRegressionIntegrationTest extends IntegrationTestSupport {
    @Test
    void upgradePreservesLegacyCalendarsRotasConnectionsAndPublishedJson() {
        String schema = schema();
        try {
            flyway(schema, "94").migrate();
            JdbcTemplate jdbc = jdbc(schema);
            seed(jdbc);
            Map<String, List<String>> before = legacyRows(jdbc);
            Flyway upgraded = flyway(schema, "101");
            assertEquals(7, upgraded.migrate().migrationsExecuted);
            assertEquals("101", upgraded.info().current().getVersion().getVersion());
            assertEquals(before, legacyRows(jdbc));
            assertEquals(2, count(jdbc, "select count(*) from worker_schedule where reason is null"));
            assertEquals(2, count(jdbc, "select count(*) from equipment_schedule where reason is null"));
            assertEquals(1, count(jdbc, "select count(*) from process_connection where delay_basis='elapsed'"));
            assertEquals(0, count(jdbc, "select count(*) from workflow_revision where snapshot_json::text like '%delayBasis%'"));
            assertEquals(0, count(jdbc, "select count(*) from worker_schedule_history"));
            assertEquals(0, count(jdbc, "select count(*) from equipment_schedule_history"));
            assertEquals(0, count(jdbc, "select count(*) from production_run_labor"));
            assertEquals(0, count(jdbc, "select count(*) from worker_shift s left join worker_schedule v on v.schedule_id=s.schedule_id "
                + "and v.project_id=s.project_id and v.user_id=s.user_id where v.schedule_id is null"));
            assertEquals(0, count(jdbc, "select count(*) from equipment_calendar s left join equipment_schedule v "
                + "on v.schedule_id=s.schedule_id and v.equipment_id=s.equipment_id where v.schedule_id is null"));

            jdbc.update("update process_connection set delay_basis='working' where connection_id='edge'");
            assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("update process_connection set delay_basis='business' where connection_id='edge'"));
            assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("update process_connection set delay_basis=null where connection_id='edge'"));
            assertEquals("working", jdbc.queryForObject("select delay_basis from process_connection where connection_id='edge'", String.class));
            upgraded.validate();
            assertEquals(0, upgraded.migrate().migrationsExecuted);
            noBom(jdbc);
        } finally {
            drop(schema);
        }
    }

    @Test
    void historySurvivesRemovalOfTheVersionItDescribesAndKeepsItsSnapshot() {
        String schema = schema();
        try {
            flyway(schema, "94").migrate();
            JdbcTemplate jdbc = jdbc(schema);
            seed(jdbc);
            flyway(schema, "101").migrate();
            String shifts = "[{\"shiftStart\":\"14:00\",\"shiftEnd\":\"20:00\",\"workDays\":[1,2,3,4,5],\"rotaWeek\":1}]";
            jdbc.update("insert into worker_schedule_history(history_id,project_id,user_id,schedule_id,effective_from,action,reason,"
                + "shifts_json,saved_by,saved_at) values ('wh','p','alice','ws1','2041-09-09','removed','Remember',?::jsonb,'owner',now())", shifts);
            jdbc.update("insert into equipment_schedule_history(history_id,equipment_id,schedule_id,effective_from,action,reason,"
                + "shifts_json,saved_by,saved_at) values ('eh','eq','es1','2041-09-09','removed','Remember',?::jsonb,'owner',now())", shifts);
            List<Map<String, Object>> workerHistory = jdbc.queryForList("select * from worker_schedule_history");
            List<Map<String, Object>> equipmentHistory = jdbc.queryForList("select * from equipment_schedule_history");
            jdbc.update("delete from worker_shift where schedule_id='ws1'");
            jdbc.update("delete from worker_schedule where schedule_id='ws1'");
            jdbc.update("delete from equipment_calendar where schedule_id='es1'");
            jdbc.update("delete from equipment_schedule where schedule_id='es1'");
            assertEquals(workerHistory, jdbc.queryForList("select * from worker_schedule_history"));
            assertEquals(equipmentHistory, jdbc.queryForList("select * from equipment_schedule_history"));
            assertEquals(0, count(jdbc, "select count(*) from worker_schedule where schedule_id='ws1'"));
            assertEquals(0, count(jdbc, "select count(*) from equipment_schedule where schedule_id='es1'"));
            noBom(jdbc);
        } finally {
            drop(schema);
        }
    }

    private void seed(JdbcTemplate jdbc) {
        jdbc.update("insert into project(project_id,project_name,owner_id) values ('p','Upgrade fixture','owner')");
        jdbc.update("insert into equipment(equipment_id,project_id,equipment_name,equipment_type) values ('eq','p','Press','machine')");
        jdbc.update("insert into worker_schedule(schedule_id,project_id,user_id,effective_from,updated_by,updated_at) values "
            + "('ws0','p','alice',null,'owner','2026-10-01T12:34:56.123456Z'),"
            + "('ws1','p','alice','2041-09-09','owner','2026-10-02T12:34:56.654321Z')");
        jdbc.update("insert into worker_shift(worker_shift_id,project_id,user_id,shift_start,shift_end,work_days,rota_week,"
            + "schedule_id,updated_by,updated_at) values "
            + "('early','p','alice','06:00','14:00','1,3,5',1,'ws0','owner',now()),"
            + "('night','p','alice','22:00','06:00','2,4,6',2,'ws0','owner',now()),"
            + "('future','p','alice','14:00','20:00','1,2,3,4,5',1,'ws1','owner',now())");
        jdbc.update("insert into worker_rota(worker_rota_id,project_id,user_id,cycle_weeks,starts_on,updated_by,updated_at,schedule_id) "
            + "values ('rota','p','alice',2,'2026-09-28','owner',now(),'ws0')");
        jdbc.update("insert into equipment_schedule(schedule_id,equipment_id,effective_from,updated_by,updated_at) values "
            + "('es0','eq',null,'owner',now()),('es1','eq','2041-09-09','owner',now())");
        jdbc.update("insert into equipment_calendar(shift_id,equipment_id,shift_start,shift_end,work_days,updated_by,updated_at,schedule_id) values "
            + "('machine_early','eq','06:00','14:00','1,2,3,4,5','owner',now(),'es0'),"
            + "('machine_future','eq','14:00','20:00','1,2,3,4,5','owner',now(),'es1')");
        jdbc.update("insert into workflow(workflow_id,project_id,workflow_name) values ('wf','p','Upgrade graph')");
        jdbc.update("insert into process(process_id,project_id,workflow_id,process_name,process_type) values "
            + "('a','p','wf','First','process'),('b','p','wf','Second','process')");
        jdbc.update("insert into process_connection(connection_id,project_id,workflow_id,from_process_id,to_process_id,delay_time_sec) "
            + "values ('edge','p','wf','a','b',1800)");
        jdbc.update("insert into workflow_revision(workflow_revision_id,workflow_id,revision_no,status,schema_version,"
            + "snapshot_json,published_by,published_at) values ('rev','wf',1,'published',4,?::jsonb,'owner',now())",
            "{\"schemaVersion\":4,\"processes\":[{\"processId\":\"a\"},{\"processId\":\"b\"}],\"connections\":["
                + "{\"connectionId\":\"edge\",\"fromProcessId\":\"a\",\"toProcessId\":\"b\",\"delayTimeSec\":1800}]}");
    }

    private Map<String, List<String>> legacyRows(JdbcTemplate jdbc) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String table : List.of("worker_schedule", "worker_shift", "worker_rota", "equipment_schedule", "equipment_calendar",
            "workflow_revision", "process_connection")) {
            String ignored = table.endsWith("_schedule") ? "-'reason'" : "process_connection".equals(table) ? "-'delay_basis'" : "";
            result.put(table, jdbc.queryForList("select (to_jsonb(r)" + ignored + ")::text from " + table + " r order by 1", String.class));
        }
        return result;
    }

    private void noBom(JdbcTemplate jdbc) {
        assertEquals(0, count(jdbc, "select count(*) from bom_header"));
        assertEquals(0, count(jdbc, "select count(*) from bom_line"));
    }

    private int count(JdbcTemplate jdbc, String sql) { return jdbc.queryForObject(sql, Integer.class); }
    private String schema() { return "schedule_followup_" + UUID.randomUUID().toString().replace("-", ""); }

    private JdbcTemplate jdbc(String schema) {
        String url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema;
        return new JdbcTemplate(new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private Flyway flyway(String schema, String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target(target).load();
    }

    private void drop(String schema) {
        assertTrue(schema.matches("schedule_followup_[a-f0-9]{32}"));
        new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()))
            .execute("drop schema if exists " + schema + " cascade");
    }
}
