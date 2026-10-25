package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/** ADR-024 team-copy audit rows are part of the same transaction as all recipients' calendars. */
@AutoConfigureMockMvc
class ScheduleHistoryTeamCopyRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void everyRecipientGetsExactlyOneHistoryRowWithTheirOwnRotaOffset() throws Exception {
        Fixture f = new Fixture();
        JsonNode source = f.history(DEMO_OWNER);
        JsonNode copied = data(DEMO_OWNER, post(f.copyPath()), f.body());
        assertEquals(f.first, copied.get(0).path("userId").asText());
        assertEquals(f.second, copied.get(1).path("userId").asText());
        assertEquals(source, f.history(DEMO_OWNER));
        for (String worker : List.of(f.first, f.second)) {
            JsonNode history = f.history(worker);
            assertEquals(1, history.size());
            JsonNode row = history.get(0);
            assertEquals("saved", row.path("action").asText());
            assertEquals("Team changed", row.path("reason").asText());
            assertEquals(DEMO_OWNER, row.path("savedBy").asText());
            assertEquals("2041-09-09", row.path("effectiveFrom").asText());
            assertEquals(2, row.path("rota").path("weeks").asInt());
            assertEquals(worker.equals(f.first) ? "2041-09-09" : "2041-09-02", row.path("rota").path("startsOn").asText());
            assertEquals(2, row.path("shifts").size());
            assertEquals("06:00", row.path("shifts").get(0).path("shiftStart").asText());
            assertEquals(1, row.path("shifts").get(0).path("rotaWeek").asInt());
            assertEquals("14:00", row.path("shifts").get(1).path("shiftStart").asText());
            assertEquals(2, row.path("shifts").get(1).path("rotaWeek").asInt());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign", "duplicate", "reason", "forbidden", "fractional"})
    void refusingAnyPartOfACopyLeavesAllRecipientsAndTheirHistoryUnchanged(String failure) throws Exception {
        Fixture f = new Fixture();
        var before = f.stored();
        Map<String, Object> body = f.body();
        String actor = DEMO_OWNER;
        if ("foreign".equals(failure)) body.put("targets", List.of(Map.of("userId", f.first), Map.of("userId", "missing-worker")));
        if ("duplicate".equals(failure)) body.put("targets", List.of(Map.of("userId", f.first), Map.of("userId", f.first)));
        if ("reason".equals(failure)) body.put("reason", "x".repeat(201));
        if ("forbidden".equals(failure)) actor = f.first;
        if ("fractional".equals(failure)) body.put("targets", List.of(Map.of("userId", f.first),
            Map.of("userId", f.second, "offsetWeeks", 0.5)));
        call(actor, post(f.copyPath()), body).andExpect(status().is("forbidden".equals(failure) ? 403 : 400));
        assertEquals(before, f.stored());
    }

    @Test
    void rollingBackTheCopyRemovesBothRecipientsAuditRowsAndVersions() throws Exception {
        Fixture f = new Fixture();
        var before = f.stored();
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            try {
                data(DEMO_OWNER, post(f.copyPath()), f.body());
                assertEquals(1, f.history(f.first).size());
                assertEquals(1, f.history(f.second).size());
                transaction.setRollbackOnly();
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        });
        assertEquals(before, f.stored());
        assertEquals(0, f.history(f.first).size());
        assertEquals(0, f.history(f.second).size());
    }

    @Test
    void simultaneousCopiesWithOppositeRecipientOrderBothComplete() throws Exception {
        Fixture f = new Fixture();
        Map<String, Object> reversed = f.body();
        reversed.put("targets", List.of(Map.of("userId", f.second, "offsetWeeks", 1), Map.of("userId", f.first, "offsetWeeks", 0)));
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            try {
                var blocker = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
                    jdbc.queryForObject("select 1 from pg_advisory_xact_lock(hashtext(?))", Integer.class,
                        "worker-shift:" + f.project + ":" + f.first);
                    jdbc.queryForObject("select 1 from pg_advisory_xact_lock(hashtext(?))", Integer.class,
                        "worker-shift:" + f.project + ":" + f.second);
                    held.countDown();
                    try {
                        assertTrue(release.await(30, TimeUnit.SECONDS));
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(error);
                    }
                }));
                assertTrue(held.await(15, TimeUnit.SECONDS));
                var first = executor.submit(() -> call(DEMO_OWNER, post(f.copyPath()), f.body()).andReturn().getResponse().getStatus());
                var second = executor.submit(() -> call(DEMO_OWNER, post(f.copyPath()), reversed).andReturn().getResponse().getStatus());
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                boolean queued = false;
                while (System.nanoTime() < deadline) {
                    Integer waiting = jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() "
                        + "and wait_event_type='Lock' and wait_event='advisory' and query like '%pg_advisory_xact_lock%'", Integer.class);
                    if (waiting != null && waiting >= 2) { queued = true; break; }
                    Thread.sleep(25);
                }
                assertTrue(queued, "Both copies must first be waiting on the controlled advisory locks");
                release.countDown();
                blocker.get(30, TimeUnit.SECONDS);
                assertEquals(List.of(200, 200), List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)));
            } finally {
                release.countDown();
            }
        }
        assertEquals(1, f.history(DEMO_OWNER).size());
        assertEquals(2, f.history(f.first).size());
        assertEquals(2, f.history(f.second).size());
    }

    private final class Fixture {
        final String project;
        final String first;
        final String second;

        Fixture() throws Exception {
            project = data(DEMO_OWNER, post("/projects"), Map.of("projectName", "Team history " + UUID.randomUUID(),
                "ownerId", DEMO_OWNER)).path("projectId").asText();
            first = member();
            second = member();
            data(DEMO_OWNER, put("/projects/" + project + "/workers/" + DEMO_OWNER + "/shifts"), Map.of(
                "effectiveFrom", "2041-09-09", "reason", "Source rota", "rota", Map.of("weeks", 2, "startsOn", "2041-09-09"),
                "shifts", List.of(Map.of("shiftStart", "06:00", "shiftEnd", "14:00", "workDays", List.of(1, 2), "rotaWeek", 1),
                    Map.of("shiftStart", "14:00", "shiftEnd", "22:00", "workDays", List.of(3, 4), "rotaWeek", 2))));
        }

        String member() {
            String user = "team-" + UUID.randomUUID();
            jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
                + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", user, user + "@test.local", DEMO_OWNER);
            jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
                UUID.randomUUID().toString(), project, user, "editor");
            return user;
        }

        String copyPath() { return "/projects/" + project + "/workers/" + DEMO_OWNER + "/shifts/copy"; }

        Map<String, Object> body() {
            return new HashMap<>(Map.of("effectiveFrom", "2041-09-09", "reason", "Team changed", "targets",
                List.of(Map.of("userId", first, "offsetWeeks", 0), Map.of("userId", second, "offsetWeeks", 1))));
        }

        JsonNode history(String user) throws Exception {
            return data(DEMO_OWNER, get("/projects/" + project + "/workers/" + user + "/schedule-history"), null);
        }

        Map<String, List<Map<String, Object>>> stored() {
            return Map.of(
                "versions", jdbc.queryForList("select * from worker_schedule where project_id=? order by schedule_id", project),
                "shifts", jdbc.queryForList("select * from worker_shift where project_id=? order by worker_shift_id", project),
                "rotas", jdbc.queryForList("select * from worker_rota where project_id=? order by worker_rota_id", project),
                "history", jdbc.queryForList("select * from worker_schedule_history where project_id=? order by history_id", project));
        }
    }

    private ResultActions call(String actor, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(actor));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        return mvc.perform(request);
    }

    private JsonNode data(String actor, MockHttpServletRequestBuilder request, Object body) throws Exception {
        return mapper.readTree(call(actor, request, body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
}
