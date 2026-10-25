package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Paged, read-only audit searches use disposable Postgres, never a development database. */
@AutoConfigureMockMvc
class ScheduleHistorySearchIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(strings = {"worker", "equipment"})
    void pagesReachOldHistoryWithoutDuplicatesWhenNewSavesArrive(String kind) throws Exception {
        Fixture f = new Fixture(kind);
        f.seed(125);
        Fixture other = new Fixture(kind);
        other.seed(3);
        var expected = f.ids();
        List<String> actual = new ArrayList<>();
        JsonNode page = f.search(DEMO_OWNER, Map.of("limit", "23"));
        assertEquals(23, page.path("entries").size());
        String firstCursor = page.path("nextCursor").asText();
        assertEquals(expected.get(22), firstCursor);
        f.seedNewer();
        int pages = 0;
        while (true) {
            pages++;
            for (JsonNode row : page.path("entries")) actual.add(row.path("historyId").asText());
            if (page.path("nextCursor").isNull()) break;
            assertTrue(pages < 10, "Paging must terminate");
            page = f.search(DEMO_OWNER, Map.of("limit", "23", "cursor", page.path("nextCursor").asText()));
        }
        assertEquals(expected, actual);
        assertEquals(125, new HashSet<>(actual).size());
        assertEquals(6, pages);
        var before = f.stored();
        JsonNode exhausted = f.search(DEMO_OWNER, Map.of("cursor", expected.get(124)));
        assertEquals(0, exhausted.path("entries").size());
        assertTrue(exhausted.path("nextCursor").isNull());
        assertEquals(before, f.stored());
        JsonNode legacy = f.data(DEMO_OWNER, get(f.history()));
        assertEquals(100, legacy.size(), "The existing endpoint keeps its contract");
    }

    @ParameterizedTest
    @ValueSource(strings = {"worker", "equipment"})
    void emptyHistoryAndAnExactFullPageHaveNoNextCursor(String kind) throws Exception {
        Fixture f = new Fixture(kind);
        JsonNode empty = f.search(DEMO_OWNER, Map.of());
        assertTrue(empty.path("entries").isEmpty());
        assertTrue(empty.path("nextCursor").isNull());
        f.seed(100);
        if (kind.equals("worker")) jdbc.update("update worker_schedule_history set rota_weeks=null,rota_starts_on=null where history_id=?", f.prefix + "100");
        JsonNode page = f.search(DEMO_OWNER, Map.of());
        assertEquals(100, page.path("entries").size());
        assertTrue(page.path("nextCursor").isNull());
        if (kind.equals("worker")) assertTrue(page.path("entries").get(0).path("rota").isNull());
    }

    @ParameterizedTest
    @ValueSource(strings = {"worker", "equipment"})
    void filtersUseStoredActorActionAndAnInclusiveExclusiveInstantRange(String kind) throws Exception {
        Fixture f = new Fixture(kind);
        f.seed(24);
        var before = f.stored();
        JsonNode page = f.search(DEMO_OWNER, Map.of("action", "removed", "savedBy", DEMO_OWNER,
            "from", "2035-01-01T09:00:02+09:00", "to", "2035-01-01T00:00:04Z"));
        assertEquals(1, page.path("entries").size());
        JsonNode row = page.path("entries").get(0);
        assertEquals(f.prefix + "012", row.path("historyId").asText());
        assertEquals("removed", row.path("action").asText());
        assertEquals(DEMO_OWNER, row.path("savedBy").asText());
        assertEquals("record 12", row.path("reason").asText());
        assertEquals("07:00:00.123456789", row.path("shifts").get(0).path("shiftStart").asText());
        if (kind.equals("worker")) {
            assertEquals(2, row.path("rota").path("weeks").asInt());
            assertEquals("2035-01-01", row.path("rota").path("startsOn").asText());
        }
        assertTrue(page.path("nextCursor").isNull());
        JsonNode empty = f.search(DEMO_OWNER, Map.of("from", "2040-01-01T00:00:00Z"));
        assertEquals(0, empty.path("entries").size());
        assertTrue(empty.path("nextCursor").isNull());
        assertEquals(before, f.stored());
    }

    @ParameterizedTest
    @ValueSource(strings = {"worker", "equipment"})
    void filteringIsAppliedBeforeTakingThePageAndCursor(String kind) throws Exception {
        Fixture f = new Fixture(kind);
        f.seed(24);
        List<String> expected = f.ids().stream().filter(id -> Integer.parseInt(id.substring(id.length() - 3)) % 3 == 0).toList();
        List<String> actual = new ArrayList<>();
        String cursor = null;
        do {
            Map<String, String> query = cursor == null ? Map.of("limit", "3", "action", "removed")
                : Map.of("limit", "3", "action", "removed", "cursor", cursor);
            JsonNode page = f.search(DEMO_OWNER, query);
            for (JsonNode row : page.path("entries")) actual.add(row.path("historyId").asText());
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
            assertTrue(actual.size() <= 8);
        } while (cursor != null);
        assertEquals(expected, actual);
    }

    @ParameterizedTest
    @CsvSource({"worker,owner,200", "equipment,owner,200", "worker,editor,200", "equipment,editor,200",
        "worker,viewer,200", "equipment,viewer,200", "worker,outsider,403", "equipment,outsider,403",
        "worker,anonymous,401", "equipment,anonymous,401", "worker,revoked,403", "equipment,revoked,403"})
    void everyPageRechecksReadAccess(String kind, String role, int expected) throws Exception {
        Fixture f = new Fixture(kind);
        f.seed(4);
        String actor = role.equals("owner") ? DEMO_OWNER : role.equals("anonymous") ? null : f.member(role.equals("revoked") ? "viewer" : role);
        String token = actor == null ? null : jwt.generateAccessToken(actor);
        if (role.equals("revoked")) {
            mvc.perform(get(f.search()).header("Authorization", "Bearer " + token).param("limit", "1")).andExpect(status().isOk());
            jdbc.update("delete from project_member where project_id=? and user_id=?", f.project, actor);
        }
        var before = f.stored();
        var request = get(f.search()).param("cursor", f.ids().get(0));
        if (token != null) request.header("Authorization", "Bearer " + token);
        mvc.perform(request).andExpect(status().is(expected));
        assertEquals(before, f.stored());
    }

    @ParameterizedTest
    @ValueSource(strings = {"worker", "equipment"})
    void aCursorFromAnotherSubjectIsRejectedEvenForTheOwnerOfBoth(String kind) throws Exception {
        Fixture f = new Fixture(kind);
        Fixture other = new Fixture(kind);
        f.seed(2);
        other.seed(2);
        var before = f.stored();
        var otherBefore = other.stored();
        JsonNode failure = f.error(Map.of("cursor", other.ids().get(0)), 400);
        assertTrue(failure.path("message").asText().contains("cursor"));
        assertEquals(before, f.stored());
        assertEquals(otherBefore, other.stored());
    }

    @ParameterizedTest
    @ValueSource(strings = {"worker", "equipment"})
    void deletedProjectsHideTheirHistory(String kind) throws Exception {
        Fixture f = new Fixture(kind);
        f.seed(2);
        jdbc.update("update project set deleted_yn='Y' where project_id=?", f.project);
        f.error(Map.of(), 404);
    }

    @Test
    void deletedEquipmentDoesNotExposeHistory() throws Exception {
        Fixture f = new Fixture("equipment");
        f.seed(2);
        jdbc.update("update equipment set deleted_yn='Y' where equipment_id=?", f.equipment);
        f.error(Map.of(), 404);
    }

    @Test
    void workersMustStillBelongToTheProject() throws Exception {
        Fixture f = new Fixture("worker");
        f.seed(2);
        jdbc.update("delete from project_member where project_id=? and user_id=?", f.project, f.worker);
        f.error(Map.of(), 404);
    }

    static Stream<Arguments> invalidFilters() {
        return Stream.of("worker", "equipment").flatMap(kind -> Stream.of(
            Map.of("limit", "0"), Map.of("limit", "101"), Map.of("limit", "1.5"), Map.of("limit", "-1"),
            Map.of("limit", "999999999999"), Map.of("action", "updated"), Map.of("action", ""),
            Map.of("savedBy", "x".repeat(51)), Map.of("savedBy", " "), Map.of("savedBy", "bad\u0000id"),
            Map.of("from", "2035-01-01"), Map.of("to", "yesterday"),
            Map.of("from", "+999999999-01-01T00:00:00Z"), Map.of("to", "-999999999-01-01T00:00:00Z"),
            Map.of("from", "2035-01-02T00:00:00Z", "to", "2035-01-01T00:00:00Z"),
            Map.of("from", "2035-01-01T09:00:00+09:00", "to", "2035-01-01T00:00:00Z"),
            Map.of("cursor", ""), Map.of("cursor", "x".repeat(51)), Map.of("cursor", "missing"))
            .map(query -> Arguments.of(kind, query)));
    }

    @ParameterizedTest
    @MethodSource("invalidFilters")
    void invalidSearchInputsAre400AndDoNotChangeStoredHistory(String kind, Map<String, String> query) throws Exception {
        Fixture f = new Fixture(kind);
        f.seed(2);
        var before = f.stored();
        JsonNode failure = f.error(query, 400);
        assertFalse(failure.path("success").asBoolean());
        assertTrue(query.keySet().stream().anyMatch(key -> failure.path("message").asText().contains(key)));
        assertEquals(before, f.stored());
    }

    private final class Fixture {
        final String kind;
        final String project;
        final String worker;
        final String equipment;
        final String prefix = "h-" + UUID.randomUUID() + "-";

        Fixture(String kind) throws Exception {
            this.kind = kind;
            project = create("/projects", Map.of("projectName", "History search " + UUID.randomUUID(), "ownerId", DEMO_OWNER)).path("projectId").asText();
            worker = kind.equals("worker") ? member("editor") : null;
            equipment = kind.equals("equipment") ? create("/equipments", Map.of("projectId", project,
                "equipmentName", "History search press", "equipmentType", "machine")).path("equipmentId").asText() : null;
        }

        String history() { return kind.equals("worker") ? "/projects/" + project + "/workers/" + worker + "/schedule-history"
            : "/equipments/" + equipment + "/calendar/history"; }
        String search() { return history() + "/search"; }
        String table() { return kind.equals("worker") ? "worker_schedule_history" : "equipment_schedule_history"; }
        String scope() { return kind.equals("worker") ? "project_id=? and user_id=?" : "equipment_id=?"; }
        Object[] scopeArgs() { return kind.equals("worker") ? new Object[]{project, worker} : new Object[]{equipment}; }

        void seed(int count) {
            String shifts = "'[{\"shiftStart\":\"07:00:00.123456789\",\"shiftEnd\":\"16:00\",\"workDays\":[1,3],\"rotaWeek\":2}]'::jsonb";
            String common = "?,date '2035-01-01',case when n%3=0 then 'removed' else 'saved' end,'record '||n," + shifts;
            if (kind.equals("worker")) jdbc.update("insert into worker_schedule_history "
                + "(history_id,project_id,user_id,schedule_id,effective_from,action,reason,shifts_json,rota_weeks,rota_starts_on,saved_by,saved_at) "
                + "select ?||lpad(n::text,3,'0'),?,?," + common + ",2,date '2035-01-01',"
                + "case when n%2=0 then ? else ? end,timestamptz '2035-01-01T00:00:00Z'+((n-1)/4)*interval '1 second' "
                + "from generate_series(1,?) n", prefix, project, worker, prefix + "version", DEMO_OWNER, worker, count);
            else jdbc.update("insert into equipment_schedule_history "
                + "(history_id,equipment_id,schedule_id,effective_from,action,reason,shifts_json,saved_by,saved_at) "
                + "select ?||lpad(n::text,3,'0'),?," + common + ",case when n%2=0 then ? else ? end,"
                + "timestamptz '2035-01-01T00:00:00Z'+((n-1)/4)*interval '1 second' from generate_series(1,?) n",
                prefix, equipment, prefix + "version", DEMO_OWNER, "historical-actor", count);
        }

        void seedNewer() {
            jdbc.update("insert into " + table() + " select "
                + (kind.equals("worker") ? "? ,project_id,user_id,schedule_id,effective_from,action,reason,shifts_json,rota_weeks,rota_starts_on,saved_by,"
                    : "? ,equipment_id,schedule_id,effective_from,action,reason,shifts_json,saved_by,")
                + "saved_at+interval '1 day' from " + table() + " where history_id=?", prefix + "new", ids().get(0));
        }

        List<String> ids() {
            return jdbc.queryForList("select history_id from " + table() + " where " + scope() + " order by saved_at desc,history_id desc", String.class, scopeArgs());
        }

        List<Map<String, Object>> stored() {
            return jdbc.queryForList("select * from " + table() + " where " + scope() + " order by history_id", scopeArgs());
        }

        String member(String role) {
            String id = "hs-" + UUID.randomUUID();
            jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
                + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER);
            if (!role.equals("outsider")) jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
                UUID.randomUUID().toString(), project, id, role);
            return id;
        }

        JsonNode search(String actor, Map<String, String> query) throws Exception { return data(actor, request(query)); }
        MockHttpServletRequestBuilder request(Map<String, String> query) {
            var request = get(search());
            query.forEach(request::param);
            return request;
        }
        JsonNode error(Map<String, String> query, int expected) throws Exception {
            var response = mvc.perform(request(query).header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER)))
                .andExpect(status().is(expected)).andReturn().getResponse();
            return mapper.readTree(response.getContentAsString());
        }
        JsonNode data(String actor, MockHttpServletRequestBuilder request) throws Exception {
            return mapper.readTree(mvc.perform(request.header("Authorization", "Bearer " + jwt.generateAccessToken(actor)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        }
        JsonNode create(String path, Object body) throws Exception {
            return data(DEMO_OWNER, post(path).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)));
        }
    }
}
