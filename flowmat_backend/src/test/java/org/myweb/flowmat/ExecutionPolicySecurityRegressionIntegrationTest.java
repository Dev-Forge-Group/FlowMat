package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** EP2: execution policy access uses current project permissions, including after revocation. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ExecutionPolicySecurityRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtProvider jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void anonymousRequestsCannotReadOrChangeAPolicy() throws Exception {
        Fixture fixture = fixture();
        mvc.perform(get(policy(fixture))).andExpect(status().isUnauthorized());
        mvc.perform(put(policy(fixture)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedVersion\":0,\"timeoutSeconds\":30}"))
            .andExpect(status().isUnauthorized());
        unchanged(fixture);
    }

    @ParameterizedTest(name = "{0}: policy read {1}, write {2}")
    @CsvSource({"viewer,200,403", "editor,200,200", "owner,200,200"})
    void projectRoleDeterminesPolicyAccess(String role, int readStatus, int writeStatus) throws Exception {
        Fixture fixture = fixture();
        String member = user();
        membership(fixture, member, role);
        String token = jwt.generateAccessToken(member);
        request(token, get(policy(fixture)), null).andExpect(status().is(readStatus));
        request(token, put(policy(fixture)), Map.of("expectedVersion", 0, "timeoutSeconds", 30))
            .andExpect(status().is(writeStatus));
        assertEquals(writeStatus == 200 ? 1L : 0L, version(fixture));
        if (writeStatus == 200) {
            assertEquals(30, jdbc.queryForObject("select timeout_seconds from process where process_id=?", Integer.class, fixture.process()));
        } else {
            unchanged(fixture);
        }
    }

    @Test
    void anEditorCannotUseAnotherProjectsNodeOrSpoofItsOwner() throws Exception {
        Fixture own = fixture();
        Fixture foreign = fixture();
        String member = user();
        membership(own, member, "editor");
        String token = jwt.generateAccessToken(member);
        request(token, get(policy(own)), null).andExpect(status().isOk());
        request(token, get(policy(foreign)), null).andExpect(status().isForbidden());
        request(token, put(policy(foreign)), Map.of("expectedVersion", 0, "timeoutSeconds", 30,
            "projectId", own.project(), "userId", DEMO_OWNER)).andExpect(status().isForbidden());
        unchanged(foreign);
    }

    @Test
    void theSameTokenLosesWriteThenReadAccessWhenMembershipChanges() throws Exception {
        Fixture fixture = fixture();
        String member = user();
        String membership = membership(fixture, member, "editor");
        String token = jwt.generateAccessToken(member);
        request(token, put(policy(fixture)), Map.of("expectedVersion", 0, "timeoutSeconds", 30))
            .andExpect(status().isOk());
        owner(put("/project-members/" + membership + "/role"), Map.of("projectRole", "viewer"));
        request(token, get(policy(fixture)), null).andExpect(status().isOk());
        request(token, put(policy(fixture)), Map.of("expectedVersion", 1, "timeoutSeconds", 60))
            .andExpect(status().isForbidden());
        owner(delete("/project-members/" + membership), null);
        request(token, get(policy(fixture)), null).andExpect(status().isForbidden());
        request(token, put(policy(fixture)), Map.of("expectedVersion", 1, "timeoutSeconds", 60))
            .andExpect(status().isForbidden());
        assertEquals(1L, version(fixture));
        assertEquals(30, jdbc.queryForObject("select timeout_seconds from process where process_id=?", Integer.class, fixture.process()));
    }

    @Test
    void deletedNodesAndMissingNodesCannotBeReadOrResurrectedThroughPolicyWrites() throws Exception {
        Fixture fixture = fixture();
        jdbc.update("update process set deleted_yn='Y' where process_id=?", fixture.process());
        for (String id : List.of(fixture.process(), UUID.randomUUID().toString())) {
            String path = "/processes/" + id + "/execution-policy";
            request(jwt.generateAccessToken(DEMO_OWNER), get(path), null).andExpect(status().isNotFound());
            request(jwt.generateAccessToken(DEMO_OWNER), put(path), Map.of("expectedVersion", 0, "timeoutSeconds", 30))
                .andExpect(status().isNotFound());
        }
        unchanged(fixture);
        assertEquals("Y", jdbc.queryForObject("select deleted_yn from process where process_id=?", String.class, fixture.process()));
    }

    @Test
    @Timeout(60)
    void conflictingConcurrentPolicySavesCannotSilentlyOverwriteTheWinner() throws Exception {
        Fixture fixture = fixture();
        String token = jwt.generateAccessToken(DEMO_OWNER);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var go = new CountDownLatch(1);
            var first = pool.submit(() -> { go.await(); return saveStatus(token, fixture, 30); });
            var second = pool.submit(() -> { go.await(); return saveStatus(token, fixture, 60); });
            go.countDown();
            var results = new ArrayList<>(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)));
            results.sort(Integer::compareTo);
            assertEquals(List.of(200, 409), results);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1L, version(fixture));
        int winner = jdbc.queryForObject("select timeout_seconds from process where process_id=?", Integer.class, fixture.process());
        assertTrue(winner == 30 || winner == 60);
        // Only the identical acknowledged change is an idempotent replay from the previous version.
        request(token, put(policy(fixture)), Map.of("expectedVersion", 0, "timeoutSeconds", winner))
            .andExpect(status().isOk());
        request(token, put(policy(fixture)), Map.of("expectedVersion", 0, "timeoutSeconds", winner == 30 ? 60 : 30))
            .andExpect(status().isConflict());
        assertEquals(1L, version(fixture));
        assertEquals(winner, jdbc.queryForObject("select timeout_seconds from process where process_id=?", Integer.class, fixture.process()));
    }

    @Test
    void policiesUnderADeletedWorkflowAreNotReadableOrWritable() throws Exception {
        Fixture fixture = fixture();
        owner(delete("/workflows/" + fixture.workflow()), null);
        String token = jwt.generateAccessToken(DEMO_OWNER);
        int read = request(token, get(policy(fixture)), null).andReturn().getResponse().getStatus();
        int write = request(token, put(policy(fixture)), Map.of("expectedVersion", 0, "timeoutSeconds", 30))
            .andReturn().getResponse().getStatus();
        assertAll("Deleted workflow policies cannot be accessed or changed",
            () -> assertEquals(404, read, "Policy read must reject the deleted parent"),
            () -> assertEquals(404, write, "Policy write must reject the deleted parent"),
            () -> unchanged(fixture));
    }

    @Test
    @Timeout(45)
    void aWorkflowDeletedWhilePolicyWriteWaitsForTheNodeLockIsRejected() throws Exception {
        Fixture fixture = fixture();
        String editor = user();
        membership(fixture, editor, "editor");
        String token = jwt.generateAccessToken(editor);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var pending = new TransactionTemplate(transactions).execute(tx -> {
                jdbc.queryForObject("select process_id from process where process_id=? for update", String.class, fixture.process());
                try {
                    owner(delete("/workflows/" + fixture.workflow()), null);
                } catch (Exception error) {
                    throw new IllegalStateException("Cannot prepare workflow deletion", error);
                }
                int blocker = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                var save = pool.submit(() -> saveStatus(token, fixture, 30));
                DatabaseContention.awaitWaitingOrDone(jdbc, save, blocker);
                return save;
            });
            assertEquals(404, pending.get(20, TimeUnit.SECONDS), "Policy save must recheck the parent after acquiring its node lock");
            unchanged(fixture);
        }
    }

    private int saveStatus(String token, Fixture fixture, int timeout) throws Exception {
        return request(token, put(policy(fixture)), Map.of("expectedVersion", 0, "timeoutSeconds", timeout))
            .andReturn().getResponse().getStatus();
    }

    private Fixture fixture() throws Exception {
        String project = owner(post("/projects"), Map.of("projectName", "Policy security " + UUID.randomUUID(),
            "ownerId", DEMO_OWNER)).path("projectId").asText();
        String workflow = owner(post("/workflows"), Map.of("projectId", project, "workflowName", "Policy")).path("workflowId").asText();
        String process = owner(post("/processes"), Map.of("workflowId", workflow, "processName", "Node")).path("processId").asText();
        return new Fixture(project, workflow, process);
    }

    private String user() {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel)"
            + " select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER);
        return id;
    }

    private String membership(Fixture fixture, String user, String role) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role,member_status,invited_by)"
            + " values(?,?,?,?,'active',?)", id, fixture.project(), user, role, DEMO_OWNER);
        return id;
    }

    private long version(Fixture fixture) {
        return jdbc.queryForObject("select execution_policy_version from process where process_id=?", Long.class, fixture.process());
    }

    private void unchanged(Fixture fixture) {
        assertEquals(0L, version(fixture));
        assertEquals(0, jdbc.queryForObject("select count(*) from process where process_id=? and timeout_seconds is not null",
            Integer.class, fixture.process()));
    }

    private static String policy(Fixture fixture) {
        return "/processes/" + fixture.process() + "/execution-policy";
    }

    private JsonNode owner(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return json.readTree(request(jwt.generateAccessToken(DEMO_OWNER), request, body).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions request(String token, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        return mvc.perform(request);
    }

    private record Fixture(String project, String workflow, String process) {}
}
