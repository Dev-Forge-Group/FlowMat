package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.domain.connector.application.ConnectorTargetPolicy;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class ProcessExecutionBindingIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtProvider jwt;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ConnectorTargetPolicy targets;

    @BeforeEach
    void policy() {
        reset(targets);
        when(targets.normalizeOrigin(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void savesReplayConflictAndClearKeepMonotonicVersions() throws Exception {
        Fixture f = fixture();
        assertEquals(0, data(call(DEMO_OWNER, get(path(f)), null)).path("version").asLong());
        JsonNode saved = data(call(DEMO_OWNER, put(path(f)), request(f, 0)));
        assertEquals(1, saved.path("version").asLong());
        assertEquals(1, data(call(DEMO_OWNER, put(path(f)), request(f, 0))).path("version").asLong());
        call(DEMO_OWNER, put(path(f)), request(f, 0).replace("\"from\":\"\"", "\"literal\":{}"))
            .andExpect(status().isConflict());
        assertEquals(2, data(call(DEMO_OWNER, put(path(f)), "{\"expectedVersion\":1}")).path("version").asLong());
        assertEquals(2, data(call(DEMO_OWNER, put(path(f)), "{\"expectedVersion\":1}")).path("version").asLong());
        call(DEMO_OWNER, put(path(f)), request(f, 0)).andExpect(status().isConflict());
        assertEquals(1, jdbc.queryForObject("select count(*) from process_execution_binding where process_id=?", Integer.class, f.process()));
        assertNull(jdbc.queryForObject("select profile_version_id from process_execution_binding where process_id=?", String.class, f.process()));
    }

    @Test
    void publishesSchemaFourOnlyWithBindingsAndFreezesMappingsAndTheProfileVersion() throws Exception {
        Fixture f = fixture();
        JsonNode initial = publish(f);
        assertEquals(3, initial.path("schemaVersion").asInt());
        assertFalse(initial.path("snapshot").has("nodeBindings"));
        data(call(DEMO_OWNER, put(path(f)), request(f, 0)));
        JsonNode first = publish(f);
        assertEquals(4, first.path("schemaVersion").asInt());
        assertEquals(4, first.path("snapshot").path("schemaVersion").asInt());
        JsonNode fixed = first.path("snapshot").path("nodeBindings");
        assertEquals(1, fixed.size());
        assertEquals(f.profileVersion(), fixed.path(0).path("profileVersionId").asText());
        assertEquals(f.output(), fixed.path(0).path("outputPortId").asText());
        assertFalse(fixed.path(0).has("baseOrigin"));
        assertFalse(fixed.path(0).has("credentialAlias"));
        data(call(DEMO_OWNER, put(path(f)), request(f, 1).replace("\"from\":\"\"", "\"literal\":{}")));
        data(call(DEMO_OWNER, put(path(f)), "{\"expectedVersion\":2}"));
        JsonNode after = data(call(DEMO_OWNER, get("/workflows/" + f.workflow() + "/revisions/"
            + first.path("workflowRevisionId").asText()), null));
        assertEquals(fixed, after.path("snapshot").path("nodeBindings"));
        assertEquals(3, publish(f).path("schemaVersion").asInt());
        data(call(DEMO_OWNER, post("/workflows/" + f.workflow() + "/revisions/"
            + first.path("workflowRevisionId").asText() + "/retire"), null));
        JsonNode retired = data(call(DEMO_OWNER, get("/workflows/" + f.workflow() + "/revisions/"
            + first.path("workflowRevisionId").asText()), null));
        assertEquals(fixed, retired.path("snapshot").path("nodeBindings"));
    }

    @Test
    void savedAndPublishedMappingsReturnExactDecimalLiterals() throws Exception {
        Fixture f = fixture();
        String body = request(f, 0).replace("\"requestMapping\":{\"from\":\"\"}",
            "\"requestMapping\":{\"literal\":9007199254740993.12345678900}")
            .replace("\"responseMapping\":{\"from\":\"\"}", "\"responseMapping\":{\"literal\":1.2300}");
        String saved = call(DEMO_OWNER, put(path(f)), body).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertTrue(saved.contains("9007199254740993.12345678900"));
        JsonNode savedData = json.readTree(saved).path("data");
        assertEquals("{\"literal\":9007199254740993.12345678900}", savedData.path("requestMappingJson").asText());
        assertEquals("{\"literal\":1.2300}", savedData.path("responseMappingJson").asText());
        JsonNode fetchedBinding = data(call(DEMO_OWNER, get(path(f)), null));
        assertEquals(savedData.path("requestMappingJson"), fetchedBinding.path("requestMappingJson"));
        assertEquals(savedData.path("responseMappingJson"), fetchedBinding.path("responseMappingJson"));
        String published = call(DEMO_OWNER, post("/workflows/" + f.workflow() + "/revisions"), null)
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(published.contains("9007199254740993.12345678900"));
        String revisionId = json.readTree(published).path("data").path("workflowRevisionId").asText();
        String fetched = call(DEMO_OWNER, get("/workflows/" + f.workflow() + "/revisions/" + revisionId), null)
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(fetched.contains("9007199254740993.12345678900"));
    }

    @Test
    void currentRolesApplyToEveryReadAndWrite() throws Exception {
        Fixture f = fixture();
        String viewer = member("viewer");
        String editor = member("editor");
        call(viewer, get(path(f)), null).andExpect(status().isOk());
        call(viewer, put(path(f)), request(f, 0)).andExpect(status().isForbidden());
        call(editor, put(path(f)), request(f, 0)).andExpect(status().isOk());
        jdbc.update("update project_member set project_role='viewer' where project_id=? and user_id=?", DEMO_PROJECT, editor);
        call(editor, put(path(f)), "{\"expectedVersion\":1}").andExpect(status().isForbidden());
        jdbc.update("delete from project_member where project_id=? and user_id=?", DEMO_PROJECT, editor);
        call(editor, get(path(f)), null).andExpect(status().isForbidden());
        mvc.perform(get(path(f))).andExpect(status().isUnauthorized());
        call("binding-outsider", get(path(f)), null).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"process", "workflow"})
    void deletedParentsCannotExposeOrChangeBindings(String table) throws Exception {
        Fixture f = fixture();
        data(call(DEMO_OWNER, put(path(f)), request(f, 0)));
        if ("process".equals(table)) jdbc.update("update process set deleted_yn='Y' where process_id=?", f.process());
        else jdbc.update("update workflow set deleted_yn='Y' where workflow_id=?", f.workflow());
        call(DEMO_OWNER, get(path(f)), null).andExpect(status().isNotFound());
        call(DEMO_OWNER, put(path(f)), "{\"expectedVersion\":1}").andExpect(status().isNotFound());
        assertEquals(1, jdbc.queryForObject("select binding_version from process_execution_binding where process_id=?", Long.class, f.process()));
    }

    @Test
    void stoppingAProfileBlocksNewBindingsAndPublication() throws Exception {
        Fixture f = fixture();
        data(call(DEMO_OWNER, put(path(f)), request(f, 0)));
        data(call(DEMO_OWNER, patch("/projects/" + DEMO_PROJECT + "/http-profiles/" + f.profile() + "/status"),
            "{\"expectedVersion\":0,\"enabled\":false}"));
        call(DEMO_OWNER, put(path(f)), request(f, 1)).andExpect(status().isConflict());
        call(DEMO_OWNER, post("/workflows/" + f.workflow() + "/revisions"), null).andExpect(status().isConflict());
        data(call(DEMO_OWNER, put(path(f)), "{\"expectedVersion\":1}"));
    }

    @Test
    void selectedPortsAndGatewayChangesAreRevalidatedOnPublication() throws Exception {
        Fixture f = fixture();
        data(call(DEMO_OWNER, put(path(f)), request(f, 0)));
        jdbc.update("update process set node_type='exclusive' where process_id=?", f.process());
        call(DEMO_OWNER, put(path(f)), request(f, 1)).andExpect(status().isConflict());
        call(DEMO_OWNER, post("/workflows/" + f.workflow() + "/revisions"), null).andExpect(status().isConflict());
        jdbc.update("update process set node_type='process' where process_id=?", f.process());
        jdbc.update("update process_io set deleted_yn='Y' where process_io_id=?", f.output());
        call(DEMO_OWNER, post("/workflows/" + f.workflow() + "/revisions"), null).andExpect(status().isConflict());
    }

    @Test
    void foreignPortsAndUnknownOperationsCannotBeStored() throws Exception {
        Fixture f = fixture();
        Fixture foreign = fixture();
        call(DEMO_OWNER, put(path(f)), request(f, 0).replace(f.output(), foreign.output())).andExpect(status().isBadRequest());
        call(DEMO_OWNER, put(path(f)), request(f, 0).replace("\"operationId\":\"read\"", "\"operationId\":\"unknown\""))
            .andExpect(status().isBadRequest());
    }

    @Test
    void malformedBodiesTypesAndInvalidMappingsAreBadRequests() throws Exception {
        Fixture f = fixture();
        for (String body : List.of("{", "null", "[]", "{}", "{\"expectedVersion\":0.5}",
            request(f, 0).replace("\"from\":\"\"", "\"from\":\"not-a-pointer\""),
            request(f, 0).replace("\"requestMapping\":{\"from\":\"\"}", "\"requestMapping\":[]"),
            request(f, 0).replace("\"operationId\":\"read\"", "\"operationId\":17"))) {
            call(DEMO_OWNER, put(path(f)), body).andExpect(status().isBadRequest());
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from process_execution_binding where process_id=?", Integer.class, f.process()));
    }

    @Test
    void oversizedDuplicateAndTrailingBodiesDoNotCreateBindings() throws Exception {
        Fixture f = fixture();
        for (String body : List.of(" ".repeat(131073), "{\"expectedVersion\":0,\"expectedVersion\":1}",
            request(f, 0) + " {}", request(f, 0).replace("{\"from\":\"\"}", "{\"from\":\"\",\"from\":\"/other\"}"))) {
            call(DEMO_OWNER, put(path(f)), body).andExpect(status().isBadRequest());
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from process_execution_binding where process_id=?", Integer.class, f.process()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"literal\":\"\\u0000\"}", "{\"literal\":\"\\ud800\"}", "{\"literal\":\"\\udc00\"}",
        "{\"literal\":{\"\\u0000\":true}}", "{\"object\":{\"\\ud800\":{\"literal\":true}}}"})
    void jsonbIncompatibleMappingTextIsRejectedAsABadRequest(String mapping) throws Exception {
        Fixture f = fixture();
        String request = request(f, 0);
        call(DEMO_OWNER, put(path(f)), request.replace("\"requestMapping\":{\"from\":\"\"}", "\"requestMapping\":" + mapping))
            .andExpect(status().isBadRequest());
        call(DEMO_OWNER, put(path(f)), request.replace("\"responseMapping\":{\"from\":\"\"}", "\"responseMapping\":" + mapping))
            .andExpect(status().isBadRequest());
        assertEquals(0, jdbc.queryForObject("select count(*) from process_execution_binding where process_id=?", Integer.class, f.process()));
    }

    @Test
    @Timeout(60)
    void concurrentDifferentChangesCannotBothUseTheSameVersion() throws Exception {
        Fixture f = fixture();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var go = new java.util.concurrent.CountDownLatch(1);
            var first = pool.submit(() -> {
                go.await();
                return call(DEMO_OWNER, put(path(f)), request(f, 0)).andReturn().getResponse().getStatus();
            });
            var second = pool.submit(() -> {
                go.await();
                return call(DEMO_OWNER, put(path(f)), request(f, 0).replace("\"from\":\"\"", "\"literal\":{}"))
                    .andReturn().getResponse().getStatus();
            });
            go.countDown();
            List<Integer> statuses = java.util.stream.Stream.of(first.get(30, java.util.concurrent.TimeUnit.SECONDS),
                second.get(30, java.util.concurrent.TimeUnit.SECONDS)).sorted().toList();
            assertEquals(List.of(200, 409), statuses);
        }
        assertEquals(1L, jdbc.queryForObject("select binding_version from process_execution_binding where process_id=?", Long.class, f.process()));
    }

    private Fixture fixture() throws Exception {
        String workflow = data(call(DEMO_OWNER, post("/workflows"), json.writeValueAsString(Map.of(
            "projectId", DEMO_PROJECT, "workflowName", "HTTP " + UUID.randomUUID())))).path("workflowId").asText();
        String process = data(call(DEMO_OWNER, post("/processes"), json.writeValueAsString(Map.of(
            "workflowId", workflow, "processName", "HTTP step")))).path("processId").asText();
        String output = data(call(DEMO_OWNER, post("/process-ios"), json.writeValueAsString(Map.of(
            "processId", process, "direction", "output", "resourceType", "data", "ioName", "Response", "requiredYn", "N"))))
            .path("processIoId").asText();
        JsonNode profile = data(call(DEMO_OWNER, post("/projects/" + DEMO_PROJECT + "/http-profiles"), """
            {"name":"Binding test","baseOrigin":"https://api.example.com","credentialAlias":null,"enabled":true,
             "operations":[{"operationId":"read","method":"GET","relativePath":"/data","successStatuses":[200]}]}
            """));
        return new Fixture(workflow, process, output, profile.path("profileId").asText(), profile.path("profileVersionId").asText());
    }

    private String member(String role) {
        String user = "binding-" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", user, user + "@test.local", DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role,member_status,created_at) values(?,?,?,?,?,now())",
            UUID.randomUUID().toString(), DEMO_PROJECT, user, role, "active");
        return user;
    }

    private JsonNode publish(Fixture f) throws Exception {
        return data(call(DEMO_OWNER, post("/workflows/" + f.workflow() + "/revisions"), null));
    }

    private String request(Fixture f, long expectedVersion) {
        return "{\"profileVersionId\":\"" + f.profileVersion() + "\",\"operationId\":\"read\",\"outputPortId\":\"" + f.output()
            + "\",\"requestMapping\":{\"from\":\"\"},\"responseMapping\":{\"from\":\"\"},\"expectedVersion\":" + expectedVersion + "}";
    }

    private String path(Fixture f) { return "/processes/" + f.process() + "/execution-binding"; }

    private JsonNode data(ResultActions action) throws Exception {
        return json.readTree(action.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions call(String user, MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(user));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request);
    }

    private record Fixture(String workflow, String process, String output, String profile, String profileVersion) {}
}
