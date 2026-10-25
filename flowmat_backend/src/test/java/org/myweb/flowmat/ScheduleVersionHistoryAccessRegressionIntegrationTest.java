package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** ADR-024 history access, failed writes, transaction atomicity and serialized saves on disposable Postgres. */
@AutoConfigureMockMvc
class ScheduleVersionHistoryAccessRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @ParameterizedTest
    @CsvSource({"worker,viewer,200", "equipment,viewer,200", "worker,outsider,403", "equipment,outsider,403",
        "worker,anonymous,401", "equipment,anonymous,401", "worker,removed,403", "equipment,removed,403"})
    void historyReadsRespectCurrentMembershipWithoutChangingRows(String kind, String role, int expected) throws Exception {
        Fixture f = new Fixture(kind);
        String actor = "anonymous".equals(role) ? null : f.member("removed".equals(role) ? "editor" : role);
        String token = actor == null ? null : jwt.generateAccessToken(actor);
        if ("removed".equals(role)) {
            mvc.perform(get(f.history()).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
            jdbc.update("delete from project_member where project_id=? and user_id=?", f.project, actor);
        }
        var before = f.stored();
        var read = get(f.history());
        if (token != null) read.header("Authorization", "Bearer " + token);
        mvc.perform(read).andExpect(status().is(expected));
        assertEquals(before, f.stored());
    }

    @ParameterizedTest
    @ValueSource(strings = {"worker", "equipment"})
    void deletingTheProjectHidesItsHistory(String kind) throws Exception {
        Fixture f = new Fixture(kind);
        jdbc.update("update project set deleted_yn='Y' where project_id=?", f.project);
        var before = f.stored();
        call(DEMO_OWNER, get(f.history()), null).andExpect(status().isNotFound());
        assertEquals(before, f.stored());
    }

    @Test
    void deletedEquipmentDoesNotExposeItsCalendarHistory() throws Exception {
        Fixture f = new Fixture("equipment");
        jdbc.update("update equipment set deleted_yn='Y' where equipment_id=?", f.equipment);
        var before = f.stored();
        call(DEMO_OWNER, get(f.history()), null).andExpect(status().isNotFound());
        assertEquals(before, f.stored());
    }

    @Test
    void anOwnerOfBothProjectsCannotMixAWorkersIdWithAnotherProject() throws Exception {
        Fixture f = new Fixture("worker");
        Fixture other = new Fixture("worker");
        var before = f.stored();
        var otherBefore = other.stored();
        call(DEMO_OWNER, get("/projects/" + f.project + "/workers/" + other.worker + "/schedule-history"), null)
            .andExpect(status().isNotFound());
        assertEquals(before, f.stored());
        assertEquals(otherBefore, other.stored());
    }

    @Test
    void membershipOfOneProjectGivesNoAccessToAnotherEquipmentsHistory() throws Exception {
        Fixture f = new Fixture("equipment");
        Fixture other = new Fixture("equipment");
        String viewer = f.member("viewer");
        var before = other.stored();
        call(viewer, get(other.history()), null).andExpect(status().isForbidden());
        assertEquals(before, other.stored());
    }

    static Stream<Arguments> invalidSaves() {
        return Stream.of("worker", "equipment").flatMap(kind -> Stream.of("time", "days", "past", "reason-length", "nul")
            .map(error -> Arguments.of(kind, error)));
    }

    @ParameterizedTest
    @MethodSource("invalidSaves")
    void rejectedSavesLeaveTheCalendarAndEveryHistoryRowIntact(String kind, String error) throws Exception {
        Fixture f = new Fixture(kind);
        Map<String, Object> body = f.body("08:00", "Changed");
        if ("time".equals(error)) body.put("shifts", List.of(shift("not-a-time", List.of(1))));
        if ("days".equals(error)) body.put("shifts", List.of(shift("08:00", List.of(0))));
        if ("past".equals(error)) body.put("effectiveFrom", "2000-01-01");
        if ("reason-length".equals(error)) body.put("reason", "x".repeat(201));
        if ("nul".equals(error)) body.put("reason", "\u0000");
        var before = f.stored();
        call(DEMO_OWNER, put(f.savePath()), body).andExpect(status().isBadRequest());
        assertEquals(before, f.stored());
    }

    @ParameterizedTest
    @ValueSource(strings = {"worker", "equipment"})
    void refusingToDeleteTheStartedVersionAddsNoRemovalHistory(String kind) throws Exception {
        Fixture f = new Fixture(kind);
        String schedule = data(DEMO_OWNER, get(f.history()), null).get(0).path("scheduleId").asText();
        var before = f.stored();
        call(DEMO_OWNER, delete(f.deletePath(schedule)), null).andExpect(status().isConflict());
        assertEquals(before, f.stored());
    }

    @ParameterizedTest
    @ValueSource(strings = {"worker", "equipment"})
    void rollingBackASuccessfulSaveAlsoRollsBackItsHistory(String kind) throws Exception {
        Fixture f = new Fixture(kind);
        var before = f.stored();
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            try {
                data(DEMO_OWNER, put(f.savePath()), f.body("08:00", "Rolled back"));
                assertEquals(2, data(DEMO_OWNER, get(f.history()), null).size());
                transaction.setRollbackOnly();
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        });
        assertEquals(before, f.stored());
        assertEquals(1, data(DEMO_OWNER, get(f.history()), null).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"worker", "equipment"})
    void simultaneousSavesKeepBothCompleteSnapshots(String kind) throws Exception {
        Fixture f = new Fixture(kind);
        String editor = "worker".equals(kind) ? f.worker : f.member("editor");
        JsonNode original = data(DEMO_OWNER, get(f.history()), null).get(0);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(20, TimeUnit.SECONDS));
                return call(DEMO_OWNER, put(f.savePath()), f.body("08:00", "Owner save")).andReturn().getResponse().getStatus();
            });
            var second = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(20, TimeUnit.SECONDS));
                return call(editor, put(f.savePath()), f.body("09:00", "Editor save")).andReturn().getResponse().getStatus();
            });
            assertTrue(ready.await(15, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(200, first.get(30, TimeUnit.SECONDS));
            assertEquals(200, second.get(30, TimeUnit.SECONDS));
        } finally {
            start.countDown();
        }
        JsonNode history = data(DEMO_OWNER, get(f.history()), null);
        assertEquals(3, history.size());
        Set<String> reasons = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode row : history) {
            assertTrue(ids.add(row.path("historyId").asText()));
            reasons.add(row.path("reason").asText());
            if ("Owner save".equals(row.path("reason").asText())) {
                assertEquals("08:00", row.path("shifts").get(0).path("shiftStart").asText());
                assertEquals(DEMO_OWNER, row.path("savedBy").asText());
            } else if ("Editor save".equals(row.path("reason").asText())) {
                assertEquals("09:00", row.path("shifts").get(0).path("shiftStart").asText());
                assertEquals(editor, row.path("savedBy").asText());
            } else assertEquals(original, row);
        }
        assertEquals(Set.of("Initial", "Owner save", "Editor save"), reasons);
    }

    @ParameterizedTest
    @ValueSource(strings = {"worker", "equipment"})
    void historyReturnsOnlyTheLatestHundredRowsOfThatSubject(String kind) throws Exception {
        Fixture f = new Fixture(kind);
        Fixture other = new Fixture(kind);
        if ("worker".equals(kind)) jdbc.update("insert into worker_schedule_history "
            + "(history_id,project_id,user_id,schedule_id,effective_from,action,reason,shifts_json,rota_weeks,rota_starts_on,saved_by,saved_at) "
            + "select 'test-'||?||'-'||n,project_id,user_id,schedule_id,effective_from,action,'Audit '||n,shifts_json,rota_weeks,rota_starts_on,"
            + "saved_by,saved_at+n*interval '1 second' from worker_schedule_history cross join generate_series(1,105) n "
            + "where project_id=? and user_id=?", UUID.randomUUID(), f.project, f.worker);
        else jdbc.update("insert into equipment_schedule_history "
            + "(history_id,equipment_id,schedule_id,effective_from,action,reason,shifts_json,saved_by,saved_at) "
            + "select 'test-'||?||'-'||n,equipment_id,schedule_id,effective_from,action,'Audit '||n,shifts_json,saved_by,"
            + "saved_at+n*interval '1 second' from equipment_schedule_history cross join generate_series(1,105) n "
            + "where equipment_id=?", UUID.randomUUID(), f.equipment);
        var before = f.stored();
        JsonNode rows = data(DEMO_OWNER, get(f.history()), null);
        assertEquals(100, rows.size());
        for (int index = 0; index < rows.size(); index++) assertEquals("Audit " + (105 - index), rows.get(index).path("reason").asText());
        assertEquals(1, data(DEMO_OWNER, get(other.history()), null).size());
        assertEquals(before, f.stored());
    }

    @ParameterizedTest
    @ValueSource(strings = {"worker", "equipment"})
    void theHistoryAuthorComesFromAuthentication(String kind) throws Exception {
        Fixture f = new Fixture(kind);
        String editor = "worker".equals(kind) ? f.worker : f.member("editor");
        Map<String, Object> body = f.body("08:00", "Verified author");
        body.put("savedBy", DEMO_OWNER);
        data(editor, put(f.savePath()), body);
        JsonNode latest = data(DEMO_OWNER, get(f.history()), null).get(0);
        assertEquals(editor, latest.path("savedBy").asText());
        assertEquals("Verified author", latest.path("reason").asText());
    }

    private final class Fixture {
        final String kind;
        final String project;
        final String worker;
        final String equipment;

        Fixture(String kind) throws Exception {
            this.kind = kind;
            project = data(DEMO_OWNER, post("/projects"), Map.of("projectName", "Version history " + UUID.randomUUID(),
                "ownerId", DEMO_OWNER)).path("projectId").asText();
            worker = "worker".equals(kind) ? member("editor") : null;
            equipment = "equipment".equals(kind) ? data(DEMO_OWNER, post("/equipments"), Map.of("projectId", project,
                "equipmentName", "History press", "equipmentType", "machine", "details", Map.of("capacityPerHour", 10)))
                .path("equipmentId").asText() : null;
            data(DEMO_OWNER, put(savePath()), body("07:00", "Initial"));
        }

        String savePath() {
            return "worker".equals(kind) ? "/projects/" + project + "/workers/" + worker + "/shifts"
                : "/equipments/" + equipment + "/calendar";
        }

        String history() {
            return "worker".equals(kind) ? "/projects/" + project + "/workers/" + worker + "/schedule-history"
                : "/equipments/" + equipment + "/calendar/history";
        }

        String deletePath(String id) {
            return "worker".equals(kind) ? "/projects/" + project + "/workers/" + worker + "/schedules/" + id
                : "/equipments/" + equipment + "/calendar/versions/" + id;
        }

        Map<String, Object> body(String starts, String reason) {
            return new HashMap<>(Map.of("shifts", List.of(shift(starts, List.of(1, 2, 3, 4, 5))), "reason", reason));
        }

        String member(String role) {
            String user = "hist-" + UUID.randomUUID();
            jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
                + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", user, user + "@test.local", DEMO_OWNER);
            if (!"outsider".equals(role)) jdbc.update(
                "insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
                UUID.randomUUID().toString(), project, user, role);
            return user;
        }

        Map<String, List<Map<String, Object>>> stored() {
            if ("worker".equals(kind)) return Map.of(
                "versions", jdbc.queryForList("select * from worker_schedule where project_id=? and user_id=? order by schedule_id", project, worker),
                "shifts", jdbc.queryForList("select * from worker_shift where project_id=? and user_id=? order by worker_shift_id", project, worker),
                "rotas", jdbc.queryForList("select * from worker_rota where project_id=? and user_id=? order by worker_rota_id", project, worker),
                "history", jdbc.queryForList("select * from worker_schedule_history where project_id=? and user_id=? order by history_id", project, worker));
            return Map.of(
                "versions", jdbc.queryForList("select * from equipment_schedule where equipment_id=? order by schedule_id", equipment),
                "shifts", jdbc.queryForList("select * from equipment_calendar where equipment_id=? order by shift_id", equipment),
                "history", jdbc.queryForList("select * from equipment_schedule_history where equipment_id=? order by history_id", equipment));
        }
    }

    private static Map<String, Object> shift(String starts, List<Integer> days) {
        return Map.of("shiftStart", starts, "shiftEnd", "16:00", "workDays", days);
    }

    private ResultActions call(String actor, MockHttpServletRequestBuilder request, Object body) throws Exception {
        if (actor != null) request.header("Authorization", "Bearer " + jwt.generateAccessToken(actor));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        return mvc.perform(request);
    }

    private JsonNode data(String actor, MockHttpServletRequestBuilder request, Object body) throws Exception {
        return mapper.readTree(call(actor, request, body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
}
