package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Upgrades disposable test schemas, without accessing any developer database. */
class WorkerScheduleVersionMigrationIntegrationTest extends IntegrationTestSupport {

    @Test
    void upgradePreservesEveryShiftAndRotaAndLinksOneInitialVersionPerWorker() {
        String schema = schema();
        JdbcTemplate jdbc = jdbc(schema);
        try {
            flyway(schema, "92").migrate();
            jdbc.update("insert into project (project_id, project_name, owner_id) values "
                + "('history_a', 'History A', 'owner'), ('history_b', 'History B', 'owner')");
            shift(jdbc, "a_early", "history_a", "alice", "06:00", "14:00", 1);
            shift(jdbc, "a_night", "history_a", "alice", "22:00", "06:00", 2);
            shift(jdbc, "b_day", "history_a", "bob", "09:00", "17:00", 1);
            shift(jdbc, "other_alice", "history_b", "alice", "10:00", "18:00", 1);
            rota(jdbc, "a_rota", "history_a", "alice");
            rota(jdbc, "rota_only", "history_a", "carol");
            rota(jdbc, "other_rota", "history_b", "alice");
            List<String> shifts = rows(jdbc, "worker_shift", "worker_shift_id");
            List<String> rotas = rows(jdbc, "worker_rota", "worker_rota_id");

            Flyway after = flyway(schema, "93");
            assertEquals(1, after.migrate().migrationsExecuted);
            assertEquals("93", after.info().current().getVersion().getVersion());
            assertEquals(shifts, rows(jdbc, "worker_shift", "worker_shift_id"));
            assertEquals(rotas, rows(jdbc, "worker_rota", "worker_rota_id"));
            assertEquals(4, count(jdbc, "select count(*) from worker_schedule"));
            assertEquals(4, count(jdbc, "select count(*) from worker_schedule where effective_from is null"));
            assertEquals(0, count(jdbc, "select count(*) from worker_shift s left join worker_schedule v "
                + "on v.schedule_id=s.schedule_id and v.project_id=s.project_id and v.user_id=s.user_id where v.schedule_id is null"));
            assertEquals(0, count(jdbc, "select count(*) from worker_rota r left join worker_schedule v "
                + "on v.schedule_id=r.schedule_id and v.project_id=r.project_id and v.user_id=r.user_id where v.schedule_id is null"));
            assertEquals(1, count(jdbc, "select count(*) from worker_shift s join worker_rota r on r.schedule_id=s.schedule_id "
                + "where s.worker_shift_id='a_night' and r.worker_rota_id='a_rota'"));
            assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("update worker_shift set schedule_id=null where worker_shift_id='a_early'"));
            assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("update worker_rota set schedule_id='missing' where worker_rota_id='a_rota'"));

            // A second version of this worker's rota is allowed, but a second version with the same start is not.
            jdbc.update("insert into worker_schedule values ('future', 'history_a', 'alice', '2041-09-09', 'owner', now())");
            jdbc.update("insert into worker_rota (worker_rota_id, project_id, user_id, cycle_weeks, starts_on, updated_by, updated_at, schedule_id) "
                + "values ('future_rota', 'history_a', 'alice', 3, '2041-09-09', 'owner', now(), 'future')");
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into worker_schedule values ('duplicate', 'history_a', 'alice', '2041-09-09', 'owner', now())"));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into worker_schedule values ('duplicate_initial', 'history_a', 'alice', null, 'owner', now())"));
            assertEquals(0, after.migrate().migrationsExecuted);
            assertEquals(0, count(jdbc, "select count(*) from bom_header"));
            assertEquals(0, count(jdbc, "select count(*) from bom_line"));
        } finally {
            drop(schema);
        }
    }

    @Test
    void upgradeWithoutWorkersDoesNotInventSchedules() {
        String schema = schema();
        try {
            flyway(schema, "92").migrate();
            assertEquals(1, flyway(schema, "93").migrate().migrationsExecuted);
            assertEquals(0, count(jdbc(schema), "select count(*) from worker_schedule"));
        } finally {
            drop(schema);
        }
    }

    private void shift(JdbcTemplate jdbc, String id, String project, String user, String start, String end, int week) {
        jdbc.update("insert into worker_shift (worker_shift_id, project_id, user_id, shift_start, shift_end, work_days, "
            + "updated_by, updated_at, rota_week) values (?, ?, ?, ?::time, ?::time, '1,3,5', 'owner', '2026-10-01T12:34:56.123456Z', ?)",
            id, project, user, start, end, week);
    }

    private void rota(JdbcTemplate jdbc, String id, String project, String user) {
        jdbc.update("insert into worker_rota (worker_rota_id, project_id, user_id, cycle_weeks, starts_on, updated_by, updated_at) "
            + "values (?, ?, ?, 2, '2026-09-28', 'owner', '2026-10-02T11:22:33.654321Z')", id, project, user);
    }

    private List<String> rows(JdbcTemplate jdbc, String table, String id) {
        return jdbc.queryForList("select (to_jsonb(r)-'schedule_id')::text from " + table + " r order by " + id, String.class);
    }

    private int count(JdbcTemplate jdbc, String sql) { return jdbc.queryForObject(sql, Integer.class); }

    private String schema() { return "schedule_upgrade_" + UUID.randomUUID().toString().replace("-", ""); }

    private JdbcTemplate jdbc(String schema) {
        String url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema;
        return new JdbcTemplate(new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private Flyway flyway(String schema, String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target(target).load();
    }

    private void drop(String schema) {
        new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()))
            .execute("drop schema " + schema + " cascade");
    }
}
