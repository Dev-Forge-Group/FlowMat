package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** WC31: a date range's ISO weekday filter must never truncate a partial day into another day. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class WorkerDayInputRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtProvider jwt;

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "6.5", "7.5"})
    void fractionalFiltersAreRejectedAndExistingDayChangesRemain(String literal) throws Exception {
        String project = projectWithWeeklyShifts();
        String bearer = "Bearer " + jwt.generateAccessToken(DEMO_OWNER);
        mvc.perform(put(worker(project) + "/days/2041-03-06").header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON).content("{\"shifts\":[{\"shiftStart\":\"08:00\",\"shiftEnd\":\"12:00\"}],\"reason\":\"Keep\"}"))
            .andExpect(status().isOk());
        var before = jdbc.queryForList("select day_shift_id,work_date,shifts,reason from worker_day_shift where project_id=? order by work_date", project);
        save(project, bearer, literal, 400);
        assertEquals(before, jdbc.queryForList("select day_shift_id,work_date,shifts,reason from worker_day_shift where project_id=? order by work_date", project));
    }

    @ParameterizedTest
    @CsvSource({"1,1", "7,7", "1.0,1", "7.0,7"})
    void wholeFiltersChooseTheExactDate(String literal, int day) throws Exception {
        String project = projectWithWeeklyShifts();
        save(project, "Bearer " + jwt.generateAccessToken(DEMO_OWNER), literal, 200);
        assertEquals(1, jdbc.queryForObject("select count(*) from worker_day_shift where project_id=?", Integer.class, project));
        assertEquals(LocalDate.parse("2041-03-04").plusDays(day - 1), jdbc.queryForObject(
            "select work_date from worker_day_shift where project_id=?", LocalDate.class, project));
    }

    private void save(String project, String bearer, String days, int expected) throws Exception {
        mvc.perform(put(worker(project) + "/days/2041-03-04").header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON).content("{\"shifts\":[],\"throughDate\":\"2041-03-10\",\"weekDays\":[" + days + "]}"))
            .andExpect(status().is(expected));
    }

    private String projectWithWeeklyShifts() throws Exception {
        String project = UUID.randomUUID().toString();
        jdbc.update("insert into project(project_id,project_name,owner_id,organization_id) "
            + "select ?,?,owner_id,organization_id from project where project_id=?", project, "Worker day input fixture", DEMO_PROJECT);
        mvc.perform(put(worker(project) + "/shifts").header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER))
            .contentType(MediaType.APPLICATION_JSON).content("{\"shifts\":[{\"shiftStart\":\"09:00\",\"shiftEnd\":\"17:00\",\"workDays\":[1,2,3,4,5,6,7]}]}"))
            .andExpect(status().isOk());
        return project;
    }

    private static String worker(String project) { return "/projects/" + project + "/workers/" + DEMO_OWNER; }
}
