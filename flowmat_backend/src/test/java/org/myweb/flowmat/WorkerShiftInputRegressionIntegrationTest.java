package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** WC2: a partial ISO weekday must not silently replace a worker's existing weekly shifts. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class WorkerShiftInputRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtProvider jwt;

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "6.5", "7.5"})
    void fractionalWeekdaysAreRejectedAndThePreviousShiftsRemain(String literal) throws Exception {
        String project = project();
        String bearer = "Bearer " + jwt.generateAccessToken(DEMO_OWNER);
        save(project, bearer, "2,4", 200);
        JsonNode before = calendar(project, bearer);
        save(project, bearer, literal, 400);
        assertEquals(before, calendar(project, bearer));
        assertEquals("2,4", jdbc.queryForObject(
            "select work_days from worker_shift where project_id=? and user_id=?", String.class, project, DEMO_OWNER));
    }

    @ParameterizedTest
    @CsvSource({"1,1", "7,7", "1.0,1", "7.0,7"})
    void wholeNumericWeekdaysKeepTheirExactDay(String literal, int day) throws Exception {
        String project = project();
        String bearer = "Bearer " + jwt.generateAccessToken(DEMO_OWNER);
        save(project, bearer, literal, 200);
        JsonNode shifts = calendar(project, bearer).path("shifts");
        assertEquals(1, shifts.size());
        assertEquals(1, shifts.get(0).path("workDays").size());
        assertEquals(day, shifts.get(0).path("workDays").get(0).asInt());
        assertEquals(Integer.toString(day), jdbc.queryForObject(
            "select work_days from worker_shift where project_id=? and user_id=?", String.class, project, DEMO_OWNER));
    }

    private void save(String project, String bearer, String days, int expectedStatus) throws Exception {
        mvc.perform(put(worker(project) + "/shifts").header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON).content(
                "{\"shifts\":[{\"shiftStart\":\"09:00\",\"shiftEnd\":\"17:00\",\"workDays\":[" + days + "]}]}"))
            .andExpect(status().is(expectedStatus));
    }

    private JsonNode calendar(String project, String bearer) throws Exception {
        return json.readTree(mvc.perform(get(worker(project)).header("Authorization", bearer))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private static String worker(String project) {
        return "/projects/" + project + "/workers/" + DEMO_OWNER;
    }

    private String project() {
        String project = UUID.randomUUID().toString();
        jdbc.update("insert into project(project_id,project_name,owner_id,organization_id) "
            + "select ?,?,owner_id,organization_id from project where project_id=?",
            project, "Worker shift input fixture", DEMO_PROJECT);
        return project;
    }
}
