package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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

/** Project access and audit integrity for labor; fixtures are BOM-free simulations in Testcontainers. */
@AutoConfigureMockMvc
class RunLaborAccessRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @ParameterizedTest
    @ValueSource(strings = {"1.0", "\"30\""})
    void exactMinutesAndExistingIntegerStringsKeepTheirValue(String literal) throws Exception {
        Fixture f = new Fixture();
        int expected = literal.equals("1.0") ? 1 : 30;
        var result = mvc.perform(post(f.path()).header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER))
            .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"" + UUID.randomUUID()
                + "\",\"workedMinutes\":" + literal + "}")).andExpect(status().isOk()).andReturn();
        assertEquals(17 + expected, mapper.readTree(result.getResponse().getContentAsString()).path("data").path("workedMinutes").asInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2.5", "1e400", "true"})
    void invalidMinuteTypesDoNotChangeAnyStoredRows(String literal) throws Exception {
        Fixture f = new Fixture();
        var before = f.stored();
        mvc.perform(post(f.path()).header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER))
            .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"" + UUID.randomUUID()
                + "\",\"workedMinutes\":" + literal + "}")).andExpect(status().isBadRequest());
        assertEquals(before, f.stored());
    }

    @ParameterizedTest
    @ValueSource(strings = {"long-user", "nul-user", "long-note"})
    void invalidTextDoesNotChangeAnyStoredRows(String kind) throws Exception {
        Fixture f = new Fixture();
        var before = f.stored();
        Map<String, Object> body = switch (kind) {
            case "long-user" -> Map.of("requestId", UUID.randomUUID(), "workedMinutes", 5, "userId", "a".repeat(51));
            case "nul-user" -> Map.of("requestId", UUID.randomUUID(), "workedMinutes", 5, "userId", "\u0000");
            default -> Map.of("requestId", UUID.randomUUID(), "workedMinutes", 5, "note", "a".repeat(501));
        };
        call(DEMO_OWNER, post(f.path()), body).andExpect(status().isBadRequest());
        assertEquals(before, f.stored());
    }

    @Test
    void aUserFromAnotherProjectCannotReceiveRecordedTime() throws Exception {
        Fixture f = new Fixture();
        Fixture other = new Fixture();
        String foreignWorker = other.member("editor");
        var before = f.stored();
        var otherBefore = other.stored();
        call(DEMO_OWNER, post(f.path()), Map.of("requestId", UUID.randomUUID(), "workedMinutes", 5, "userId", foreignWorker))
            .andExpect(status().isBadRequest());
        assertEquals(before, f.stored());
        assertEquals(otherBefore, other.stored());
    }

    @Test
    void aFiveHundredCharacterNoteRemainsSupported() throws Exception {
        Fixture f = new Fixture();
        JsonNode response = data(DEMO_OWNER, post(f.path()), Map.of("requestId", UUID.randomUUID(), "workedMinutes", 5,
            "note", "a".repeat(500)));
        assertEquals("a".repeat(500), response.path("lines").get(1).path("note").asText());
    }

    @Test
    void simultaneousIdenticalRequestsOnlyAppendOneLaborRecord() throws Exception {
        Fixture f = new Fixture();
        var start = new CountDownLatch(1);
        var body = Map.of("requestId", UUID.randomUUID(), "workedMinutes", 5);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { await(start); return call(DEMO_OWNER, post(f.path()), body)
                .andReturn().getResponse().getStatus(); });
            var second = executor.submit(() -> { await(start); return call(DEMO_OWNER, post(f.path()), body)
                .andReturn().getResponse().getStatus(); });
            start.countDown();
            assertEquals(200, first.get(15, TimeUnit.SECONDS));
            assertEquals(200, second.get(15, TimeUnit.SECONDS));
        } finally {
            start.countDown();
        }
        JsonNode stored = data(DEMO_OWNER, get(f.path()), null);
        assertEquals(2, stored.path("lines").size());
        assertEquals(22, stored.path("workedMinutes").asInt());
    }

    @Test
    void competingAuthorsCannotShareAnIdempotencyKey() throws Exception {
        Fixture f = new Fixture();
        String editor = f.member("editor");
        var start = new CountDownLatch(1);
        var body = Map.of("requestId", UUID.randomUUID(), "workedMinutes", 5, "userId", DEMO_OWNER);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { await(start); return call(DEMO_OWNER, post(f.path()), body)
                .andReturn().getResponse().getStatus(); });
            var second = executor.submit(() -> { await(start); return call(editor, post(f.path()), body)
                .andReturn().getResponse().getStatus(); });
            start.countDown();
            var statuses = new ArrayList<>(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)));
            Collections.sort(statuses);
            assertEquals(List.of(200, 409), statuses);
        } finally {
            start.countDown();
        }
        JsonNode stored = data(DEMO_OWNER, get(f.path()), null);
        assertEquals(2, stored.path("lines").size());
        assertEquals(22, stored.path("workedMinutes").asInt());
    }

    @Test
    void aRecordWaitingForTheActualFinishTransactionIsRefusedAfterItCommits() throws Exception {
        Fixture f = new Fixture();
        var before = jdbc.queryForList("select * from production_run_labor where production_run_id=? order by run_labor_id", f.run);
        var finished = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var finishing = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
                    try {
                        call(DEMO_OWNER, post("/production-runs/" + f.run + "/finish"), Map.of("actualOutputQty", 1))
                            .andExpect(status().isOk());
                        finished.countDown();
                        await(commit);
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                }));
                assertTrue(finished.await(15, TimeUnit.SECONDS), "The finish operation must hold its transaction open");
                var recording = executor.submit(() -> call(DEMO_OWNER, post(f.path()),
                    Map.of("requestId", UUID.randomUUID(), "workedMinutes", 5)).andReturn().getResponse().getStatus());
                awaitRunRowLock();
                commit.countDown();
                finishing.get(15, TimeUnit.SECONDS);
                assertEquals(400, recording.get(15, TimeUnit.SECONDS));
            } finally {
                commit.countDown();
            }
        }
        assertEquals("finished", jdbc.queryForObject("select run_status from production_run where production_run_id=?", String.class, f.run));
        assertEquals(before, jdbc.queryForList("select * from production_run_labor where production_run_id=? order by run_labor_id", f.run));
    }

    private void await(CountDownLatch signal) throws InterruptedException {
        if (!signal.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Test coordination timed out");
    }

    private void awaitRunRowLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waits = jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() "
                + "and wait_event_type='Lock' and query ilike '%production_run%' and query ilike '%for%update%'", Integer.class);
            if (waits != null && waits > 0) return;
            Thread.sleep(25);
        }
        fail("The competing labor command never waited on the run row lock");
    }

    @Test
    void viewerReadsLaborAndComparisonWithoutChangingStoredRows() throws Exception {
        Fixture f = new Fixture();
        String viewer = f.member("viewer");
        var before = f.stored();
        assertEquals(data(DEMO_OWNER, get(f.path()), null), data(viewer, get(f.path()), null));
        assertEquals(data(DEMO_OWNER, get(f.comparison()), null), data(viewer, get(f.comparison()), null));
        assertEquals(before, f.stored());
    }

    @ParameterizedTest
    @ValueSource(strings = {"record", "cancel"})
    void viewerCannotChangeLabor(String action) throws Exception {
        Fixture f = new Fixture();
        String viewer = f.member("viewer");
        var before = f.stored();
        f.request(action, "Bearer " + jwt.generateAccessToken(viewer)).andExpect(status().isForbidden());
        assertEquals(before, f.stored());
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "comparison", "record", "cancel"})
    void outsiderCannotReadOrChangeLabor(String action) throws Exception {
        Fixture f = new Fixture();
        var before = f.stored();
        f.request(action, "Bearer " + jwt.generateAccessToken(f.member("outsider")))
            .andExpect(status().isForbidden());
        assertEquals(before, f.stored());
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "comparison", "record", "cancel"})
    void anonymousCannotReadOrChangeLabor(String action) throws Exception {
        Fixture f = new Fixture();
        var before = f.stored();
        f.request(action, null).andExpect(status().isUnauthorized());
        assertEquals(before, f.stored());
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "comparison", "record", "cancel"})
    void removingMembershipRevokesTheSamePreviouslyUsableToken(String action) throws Exception {
        Fixture f = new Fixture();
        String editor = f.member("editor");
        String token = "Bearer " + jwt.generateAccessToken(editor);
        mvc.perform(get(f.path()).header("Authorization", token)).andExpect(status().isOk());
        jdbc.update("delete from project_member where project_id=? and user_id=?", f.project, editor);
        var before = f.stored();
        f.request(action, token).andExpect(status().isForbidden());
        assertEquals(before, f.stored());
    }

    @Test
    void evenAnOwnerOfBothProjectsCannotCancelAnotherRunsRecord() throws Exception {
        Fixture f = new Fixture();
        Fixture other = new Fixture();
        var before = f.stored();
        var otherBefore = other.stored();
        call(DEMO_OWNER, post(f.path() + "/" + other.line + "/cancel"), Map.of("reason", "Wrong parent"))
            .andExpect(status().isNotFound());
        assertEquals(before, f.stored());
        assertEquals(otherBefore, other.stored());
    }

    @Test
    void editorRecordsAsTheAuthenticatedAuthorAndCancelsWithoutDeletingAudit() throws Exception {
        Fixture f = new Fixture();
        String editor = f.member("editor");
        JsonNode recorded = data(editor, post(f.path()), Map.of("requestId", UUID.randomUUID(), "workedMinutes", 23,
            "recordedBy", DEMO_OWNER));
        JsonNode line = recorded.path("lines").get(1);
        assertEquals(editor, line.path("userId").asText());
        assertEquals(editor, line.path("recordedBy").asText());
        JsonNode cancelled = data(editor, post(f.path() + "/" + line.path("runLaborId").asText() + "/cancel"),
            Map.of("reason", "Wrong duration"));
        assertEquals(2, cancelled.path("lines").size());
        assertTrue(cancelled.path("lines").get(1).path("cancelled").asBoolean());
        assertEquals(editor, cancelled.path("lines").get(1).path("cancelledBy").asText());
        assertEquals(17, cancelled.path("workedMinutes").asInt());
    }

    @Test
    void aDifferentAuthorCannotReuseAnExistingRequestIdEvenWithIdenticalContent() throws Exception {
        Fixture f = new Fixture();
        String editor = f.member("editor");
        var before = f.stored();
        call(editor, post(f.path()), Map.of("requestId", f.requestId, "userId", DEMO_OWNER, "workedMinutes", 17))
            .andExpect(status().isConflict());
        assertEquals(before, f.stored());
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "record", "cancel"})
    void deletedRunsCannotExposeOrChangeLabor(String action) throws Exception {
        Fixture f = new Fixture();
        jdbc.update("update production_run set deleted_yn='Y' where production_run_id=?", f.run);
        var before = f.stored();
        f.request(action, "Bearer " + jwt.generateAccessToken(DEMO_OWNER)).andExpect(status().isNotFound());
        assertEquals(before, f.stored());
    }

    private final class Fixture {
        final String project;
        final String order;
        final String run;
        final String line;
        final UUID requestId = UUID.randomUUID();

        Fixture() throws Exception {
            project = data(DEMO_OWNER, post("/projects"), Map.of("projectName", "Labor access " + UUID.randomUUID(),
                "ownerId", DEMO_OWNER)).path("projectId").asText();
            String workflow = data(DEMO_OWNER, post("/workflows"), Map.of("projectId", project, "workflowName", "Labor"))
                .path("workflowId").asText();
            data(DEMO_OWNER, post("/processes"), Map.of("workflowId", workflow, "processName", "Work", "nodeType", "process"));
            String revision = data(DEMO_OWNER, post("/workflows/" + workflow + "/revisions"), null)
                .path("workflowRevisionId").asText();
            order = data(DEMO_OWNER, post("/work-orders"), Map.of("projectId", project, "workflowId", workflow,
                "workOrderTitle", "Labor access", "targetQuantity", 1)).path("workOrderId").asText();
            data(DEMO_OWNER, post("/work-orders/" + order + "/approve"), null);
            JsonNode started = data(DEMO_OWNER, post("/production-runs/start"), Map.of("projectId", project,
                "workflowId", workflow, "workflowRevisionId", revision, "workOrderId", order,
                "plannedOutputQty", 1, "runType", "simulation"));
            assertTrue(started.path("bomId").isNull());
            run = started.path("productionRunId").asText();
            line = data(DEMO_OWNER, post(path()), Map.of("requestId", requestId, "workedMinutes", 17))
                .path("lines").get(0).path("runLaborId").asText();
        }

        String path() { return "/production-runs/" + run + "/labor"; }
        String comparison() { return "/work-orders/" + order + "/labor-comparison"; }

        ResultActions request(String action, String bearer) throws Exception {
            MockHttpServletRequestBuilder request = switch (action) {
                case "read" -> get(path());
                case "comparison" -> get(comparison());
                case "record" -> post(path()).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of("requestId", UUID.randomUUID(), "workedMinutes", 5)));
                case "cancel" -> post(path() + "/" + line + "/cancel").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"Wrong duration\"}");
                default -> throw new IllegalArgumentException(action);
            };
            if (bearer != null) request.header("Authorization", bearer);
            return mvc.perform(request);
        }

        String member(String role) {
            String id = "labor-" + UUID.randomUUID();
            jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
                + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER);
            if (!"outsider".equals(role)) jdbc.update(
                "insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
                UUID.randomUUID().toString(), project, id, role);
            return id;
        }

        Map<String, List<Map<String, Object>>> stored() {
            return Map.of("run", jdbc.queryForList("select * from production_run where production_run_id=?", run),
                "order", jdbc.queryForList("select * from work_order where work_order_id=?", order),
                "labor", jdbc.queryForList("select * from production_run_labor where production_run_id=? order by run_labor_id", run));
        }
    }

    private ResultActions call(String actor, MockHttpServletRequestBuilder request, Object body) throws Exception {
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        return mvc.perform(request.header("Authorization", "Bearer " + jwt.generateAccessToken(actor)));
    }

    private JsonNode data(String actor, MockHttpServletRequestBuilder request, Object body) throws Exception {
        return mapper.readTree(call(actor, request, body).andExpect(status().isOk()).andReturn().getResponse()
            .getContentAsString()).path("data");
    }
}
