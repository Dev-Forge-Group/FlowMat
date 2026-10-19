package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.domain.flowrun.application.publicapi.LinkedRunWorkingTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** The documented one-year window must not silently shrink to a smaller number of daily intersections. */
@AutoConfigureMockMvc
@Transactional
class LinkedWorkingTimeLongHorizonRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LinkedRunWorkingTime working;

    @ParameterizedTest
    @ValueSource(ints = {150, 300})
    void theFirstCommonShiftWithinAYearIsFoundEvenAfterManyDisjointDailyShifts(int daysAhead) {
        OffsetDateTime ready = OffsetDateTime.parse("2041-09-09T08:00:00+09:00");
        String key = UUID.randomUUID().toString();
        String project = "p" + key;
        String order = "w" + key;
        String run = "r" + key;
        String first = "a" + key;
        String second = "b" + key;
        jdbc.update("insert into project(project_id,project_name,owner_id) values(?, 'Long working-time window', ?)", project, DEMO_OWNER);
        jdbc.update("insert into work_order(work_order_id,project_id,work_order_number,work_order_title,target_quantity) "
            + "values(?,?,?,'Long window',1)", order, project, order);
        jdbc.update("insert into production_run(production_run_id,project_id,work_order_id,run_number,run_type,planned_output_qty) "
            + "values(?,?,?,?,'simulation',1)", run, project, order, key.substring(0, 28));
        for (String worker : new String[] {first, second}) {
            jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
                + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", worker, worker + "@test.local", DEMO_OWNER);
            jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,'editor')",
                UUID.randomUUID().toString(), project, worker);
            jdbc.update("insert into work_order_crew(crew_id,work_order_id,user_id,added_by,added_at,share_percent) values(?,?,?,?,now(),100)",
                UUID.randomUUID().toString(), order, worker, DEMO_OWNER);
        }
        schedule(project, first, null, "09:00", "10:00");
        schedule(project, second, null, "10:00", "11:00");
        schedule(project, second, ready.toLocalDate().plusDays(daysAhead).toString(), "09:00", "10:00");

        var result = working.workingStart(run, ready);
        assertTrue(result.isPresent(), "There is a common shift within 366 days, after " + daysAhead + " disjoint days");
        assertEquals(ready.plusDays(daysAhead).plusHours(1).toInstant(), result.orElseThrow().at().toInstant());
    }

    private void schedule(String project, String user, String effectiveFrom, String start, String end) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into worker_schedule(schedule_id,project_id,user_id,effective_from,updated_by,updated_at) "
            + "values(?,?,?,?::date,?,now())", id, project, user, effectiveFrom, DEMO_OWNER);
        jdbc.update("insert into worker_shift(worker_shift_id,project_id,user_id,shift_start,shift_end,work_days,updated_by,updated_at,rota_week,schedule_id) "
            + "values(?,?,?,?::time,?::time,'1,2,3,4,5,6,7',?,now(),1,?)", UUID.randomUUID().toString(), project, user, start, end, DEMO_OWNER, id);
    }
}
