package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Published-only revision comparison, pagination, precise JSON and access checks on disposable Postgres. */
@AutoConfigureMockMvc
class WorkflowRevisionComparisonIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtProvider jwt;

    @Test
    void comparesFrozenRevisionsInEitherDirectionWithoutUsingOrChangingTheDraft() throws Exception {
        Fixture f = new Fixture();
        f.call(DEMO_OWNER, put("/processes/" + f.process), Map.of("processName", "Unpublished third edit"));
        var before = f.stored();
        JsonNode diff = f.compare(DEMO_OWNER, Map.of());
        assertEquals(f.workflow, diff.path("workflowId").asText());
        assertEquals(f.first, diff.path("fromRevision").path("revisionId").asText());
        assertEquals(f.second, diff.path("toRevision").path("revisionId").asText());
        JsonNode name = changes(diff).stream().filter(x -> x.path("path").asText().equals("/processName")).findFirst().orElseThrow();
        assertEquals("processes", name.path("section").asText());
        assertEquals(f.process, name.path("entityId").asText());
        assertEquals("modified", name.path("kind").asText());
        assertEquals("Original node", name.path("before").asText());
        assertEquals("Changed node", name.path("after").asText());
        assertTrue(diff.path("nextOffset").isNull());
        assertEquals(before, f.stored());
        JsonNode reverse = f.compare(DEMO_OWNER, Map.of("fromRevisionId", f.second, "toRevisionId", f.first));
        JsonNode reversed = changes(reverse).stream().filter(x -> x.path("path").asText().equals("/processName")).findFirst().orElseThrow();
        assertEquals("Changed node", reversed.path("before").asText());
        assertEquals("Original node", reversed.path("after").asText());
        assertEquals(diff.path("totalChanges"), reverse.path("totalChanges"));
        assertEquals(before, f.stored());
    }

    @Test
    void aRetiredRevisionAndTheSameRevisionCanStillBeCompared() throws Exception {
        Fixture f = new Fixture();
        f.call(DEMO_OWNER, post("/workflows/" + f.workflow + "/revisions/" + f.first + "/retire"), null);
        var before = f.stored();
        assertEquals("retired", f.compare(DEMO_OWNER, Map.of()).path("fromRevision").path("status").asText());
        JsonNode same = f.compare(DEMO_OWNER, Map.of("toRevisionId", f.first));
        assertEquals(0, same.path("totalChanges").asInt());
        assertTrue(same.path("changes").isEmpty());
        assertTrue(same.path("nextOffset").isNull());
        assertEquals(before, f.stored());
    }

    @Test
    void pagesEveryDifferenceAndRetainsLargeDecimalPrecision() throws Exception {
        Fixture f = new Fixture();
        jdbc.update("update workflow_revision set snapshot_json=?::jsonb where workflow_revision_id=?",
            "{\"schemaVersion\":3,\"workflow\":{\"workflowName\":\"A\"},\"connections\":[{\"connectionId\":\"c\",\"capacity\":9007199254740993.123456789}],\"simulationConfig\":{\"a\":1,\"b\":2,\"c\":3}}", f.first);
        jdbc.update("update workflow_revision set snapshot_json=?::jsonb where workflow_revision_id=?",
            "{\"schemaVersion\":3,\"workflow\":{\"workflowName\":\"B\"},\"connections\":[{\"connectionId\":\"c\",\"capacity\":9007199254740993.123456790}],\"simulationConfig\":{\"a\":4,\"b\":5,\"c\":6}}", f.second);
        var before = f.stored();
        JsonNode all = f.compare(DEMO_OWNER, Map.of());
        assertEquals(5, all.path("totalChanges").asInt());
        JsonNode capacity = changes(all).stream().filter(x -> x.path("path").asText().equals("/capacity")).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("9007199254740993.123456789").compareTo(capacity.path("before").decimalValue()));
        assertEquals(0, new BigDecimal("9007199254740993.123456790").compareTo(capacity.path("after").decimalValue()));
        List<JsonNode> paged = new ArrayList<>();
        int offset = 0;
        do {
            JsonNode page = f.compare(DEMO_OWNER, Map.of("limit", "2", "offset", String.valueOf(offset)));
            assertEquals(5, page.path("totalChanges").asInt());
            paged.addAll(changes(page));
            if (page.path("nextOffset").isNull()) break;
            offset = page.path("nextOffset").asInt();
            assertTrue(offset <= 4);
        } while (true);
        assertEquals(changes(all), paged);
        JsonNode exhausted = f.compare(DEMO_OWNER, Map.of("offset", String.valueOf(Integer.MAX_VALUE)));
        assertTrue(exhausted.path("changes").isEmpty());
        assertTrue(exhausted.path("nextOffset").isNull());
        assertEquals(5, exhausted.path("totalChanges").asInt());
        assertEquals(before, f.stored());
    }

    @ParameterizedTest
    @CsvSource({"owner,200", "editor,200", "viewer,200", "outsider,403", "anonymous,401", "revoked,403"})
    void comparesOnlyWithinCurrentReadAccess(String role, int expected) throws Exception {
        Fixture f = new Fixture();
        String actor = role.equals("owner") ? DEMO_OWNER : role.equals("anonymous") ? null : f.member(role.equals("revoked") ? "viewer" : role);
        String token = actor == null ? null : jwt.generateAccessToken(actor);
        if (role.equals("revoked")) {
            mvc.perform(f.request(Map.of()).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
            jdbc.update("delete from project_member where project_id=? and user_id=?", f.project, actor);
        }
        var before = f.stored();
        var request = f.request(Map.of());
        if (token != null) request.header("Authorization", "Bearer " + token);
        mvc.perform(request).andExpect(status().is(expected));
        assertEquals(before, f.stored());
    }

    @ParameterizedTest
    @CsvSource({"fromRevisionId", "toRevisionId"})
    void rejectsAnotherWorkflowsRevisionEvenWhenBothAreOwned(String parameter) throws Exception {
        Fixture f = new Fixture();
        Fixture other = new Fixture();
        f.error(Map.of(parameter, other.first), 404);
    }

    @ParameterizedTest
    @CsvSource({"project", "workflow"})
    void deletedParentsHideTheComparison(String deleted) throws Exception {
        Fixture f = new Fixture();
        if (deleted.equals("project")) jdbc.update("update project set deleted_yn='Y' where project_id=?", f.project);
        else jdbc.update("update workflow set deleted_yn='Y' where workflow_id=?", f.workflow);
        f.error(Map.of(), 404);
    }

    static Stream<Arguments> invalidParameters() {
        return Stream.of(Arguments.of("limit", "0"), Arguments.of("limit", "501"), Arguments.of("limit", "1.5"),
            Arguments.of("limit", ""), Arguments.of("limit", "999999999999"), Arguments.of("offset", "-1"),
            Arguments.of("offset", "0.1"), Arguments.of("offset", ""), Arguments.of("offset", "999999999999"),
            Arguments.of("fromRevisionId", " "), Arguments.of("toRevisionId", ""),
            Arguments.of("fromRevisionId", "x".repeat(51)), Arguments.of("toRevisionId", "bad\u0000id"));
    }

    @ParameterizedTest
    @MethodSource("invalidParameters")
    void malformedParametersAre400WithAFieldName(String parameter, String value) throws Exception {
        Fixture f = new Fixture();
        var before = f.stored();
        JsonNode failure = f.error(Map.of(parameter, value), 400);
        assertTrue(failure.path("message").asText().contains(parameter));
        assertEquals(before, f.stored());
    }

    @Test
    void missingRequiredRevisionParameterIs400() throws Exception {
        Fixture f = new Fixture();
        mvc.perform(get(f.path()).param("toRevisionId", f.second).header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void malformedStoredCollectionsAreReportedWithoutLeakingTheirContents() throws Exception {
        Fixture f = new Fixture();
        jdbc.update("update workflow_revision set snapshot_json=?::jsonb where workflow_revision_id=?",
            "{\"processes\":[{\"processId\":\"secret-marker\"},{\"processId\":\"secret-marker\"}]}", f.first);
        var before = f.stored();
        JsonNode failure = f.error(Map.of(), 409);
        assertTrue(failure.path("message").asText().contains("processes"));
        assertFalse(failure.toString().contains("secret-marker"));
        assertEquals(before, f.stored());
    }

    private static List<JsonNode> changes(JsonNode page) {
        List<JsonNode> result = new ArrayList<>();
        page.path("changes").forEach(result::add);
        return result;
    }

    private final class Fixture {
        final String project;
        final String workflow;
        final String process;
        final String first;
        final String second;

        Fixture() throws Exception {
            project = call(DEMO_OWNER, post("/projects"), Map.of("projectName", "Revision compare " + UUID.randomUUID(), "ownerId", DEMO_OWNER)).path("projectId").asText();
            workflow = call(DEMO_OWNER, post("/workflows"), Map.of("projectId", project, "workflowName", "Comparison draft")).path("workflowId").asText();
            process = call(DEMO_OWNER, post("/processes"), Map.of("workflowId", workflow, "processName", "Original node")).path("processId").asText();
            first = call(DEMO_OWNER, post("/workflows/" + workflow + "/revisions"), null).path("workflowRevisionId").asText();
            call(DEMO_OWNER, put("/processes/" + process), Map.of("processName", "Changed node"));
            second = call(DEMO_OWNER, post("/workflows/" + workflow + "/revisions"), null).path("workflowRevisionId").asText();
        }

        String path() { return "/workflows/" + workflow + "/revisions/compare"; }
        MockHttpServletRequestBuilder request(Map<String, String> parameters) {
            var request = get(path());
            request.param("fromRevisionId", parameters.getOrDefault("fromRevisionId", first));
            request.param("toRevisionId", parameters.getOrDefault("toRevisionId", second));
            parameters.forEach((key, value) -> { if (!key.equals("fromRevisionId") && !key.equals("toRevisionId")) request.param(key, value); });
            return request;
        }
        JsonNode compare(String actor, Map<String, String> query) throws Exception { return call(actor, request(query), null); }
        JsonNode error(Map<String, String> query, int expected) throws Exception {
            String json = mvc.perform(request(query).header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER)))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
            return mapper.readTree(json);
        }
        JsonNode call(String actor, MockHttpServletRequestBuilder request, Object body) throws Exception {
            request.header("Authorization", "Bearer " + jwt.generateAccessToken(actor));
            if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
            String json = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            return mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).<JsonNode>readTree(json).path("data");
        }
        String member(String role) {
            String id = "rc-" + UUID.randomUUID();
            jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER);
            if (!role.equals("outsider")) jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
                UUID.randomUUID().toString(), project, id, role);
            return id;
        }
        Map<String, List<Map<String, Object>>> stored() {
            return Map.of("revisions", jdbc.queryForList("select * from workflow_revision where workflow_id=? order by revision_no", workflow),
                "draft", jdbc.queryForList("select * from workflow where workflow_id=?", workflow),
                "processes", jdbc.queryForList("select * from process where workflow_id=? order by process_id", workflow));
        }
    }
}
