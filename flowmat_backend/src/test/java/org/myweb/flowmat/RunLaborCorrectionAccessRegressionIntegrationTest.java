package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
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

/** LB9-LB11 access and approval races on BOM-free simulations in disposable Postgres. */
@AutoConfigureMockMvc
class RunLaborCorrectionAccessRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(strings = {"read", "request", "approve", "reject"})
    void anonymousRequestsCannotReadOrChangeCorrections(String action) throws Exception {
        Fixture f = new Fixture(action);
        var before = f.stored();
        f.action(action, null).andExpect(status().isUnauthorized());
        assertEquals(before, f.stored());
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "request", "approve", "reject"})
    void outsidersCannotReadOrChangeCorrections(String action) throws Exception {
        Fixture f = new Fixture(action);
        String outsider = f.member("outsider");
        var before = f.stored();
        f.action(action, outsider).andExpect(status().isForbidden());
        assertEquals(before, f.stored());
    }

    @ParameterizedTest
    @ValueSource(strings = {"request", "approve", "reject"})
    void viewersCannotRequestOrDecideCorrections(String action) throws Exception {
        Fixture f = new Fixture(action);
        String viewer = f.member("viewer");
        var before = f.stored();
        f.action(action, viewer).andExpect(status().isForbidden());
        assertEquals(before, f.stored());
    }

    @Test
    void viewersCanReadTheCorrectionWithoutChangingAnyRows() throws Exception {
        Fixture f = new Fixture("read");
        String viewer = f.member("viewer");
        var before = f.stored();
        JsonNode corrections = data(f.action("read", viewer));
        assertEquals(1, corrections.size());
        assertEquals(f.correction, corrections.get(0).path("productionRunCorrectionId").asText());
        assertEquals(f.line, corrections.get(0).path("lines").get(0).path("targetRunLaborId").asText());
        assertEquals(before, f.stored());
    }

    @ParameterizedTest
    @ValueSource(strings = {"approve", "reject"})
    void editorsCannotDecideCorrectionsEvenWhenTheyRequestedThem(String action) throws Exception {
        Fixture f = new Fixture("request");
        String editor = f.member("editor");
        f.request(editor);
        var before = f.stored();
        f.action(action, editor).andExpect(status().isForbidden());
        assertEquals(before, f.stored());
    }

    @Test
    void anEditorsRequestChangesNoLaborUntilTheOwnerApprovesIt() throws Exception {
        Fixture f = new Fixture("request");
        String editor = f.member("editor");
        var laborBefore = f.laborRows();
        JsonNode requested = f.request(editor);
        assertEquals(editor, requested.path("requestedBy").asText());
        assertEquals("pending_approval", requested.path("status").asText());
        assertEquals(laborBefore, f.laborRows());
        JsonNode applied = data(f.action("approve", DEMO_OWNER));
        assertEquals("applied", applied.path("status").asText());
        assertEquals(DEMO_OWNER, applied.path("decidedBy").asText());
        f.assertAppliedOnce();
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "request", "approve", "reject"})
    void removalRevokesAccessEvenWithTheSamePreviouslyValidToken(String action) throws Exception {
        Fixture f = new Fixture(action);
        String editor = f.member("editor");
        String token = jwt.generateAccessToken(editor);
        mvc.perform(get(f.path()).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        jdbc.update("delete from project_member where project_id=? and user_id=?", f.project, editor);
        var before = f.stored();
        mvc.perform(f.requestFor(action).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        assertEquals(before, f.stored());
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "request", "approve", "reject"})
    void deletedRunsCannotExposeOrChangeCorrections(String action) throws Exception {
        Fixture f = new Fixture(action);
        jdbc.update("update production_run set deleted_yn='Y' where production_run_id=?", f.run);
        var before = f.stored();
        f.action(action, DEMO_OWNER).andExpect(status().isNotFound());
        assertEquals(before, f.stored());
    }

    @Test
    void anotherRunsLaborCannotBeCancelledEvenForAnOwnerOfBothProjects() throws Exception {
        Fixture f = new Fixture("request");
        Fixture other = new Fixture("request");
        var before = f.stored();
        var otherBefore = other.stored();
        call(DEMO_OWNER, post(f.path()), Map.of("reason", "Wrong run", "lines",
            List.of(Map.of("kind", "cancel_labor", "targetRunLaborId", other.line)))).andExpect(status().isNotFound());
        assertEquals(before, f.stored());
        assertEquals(otherBefore, other.stored());
    }

    @ParameterizedTest
    @ValueSource(strings = {"approve", "reject"})
    void anotherRunsCorrectionCannotBeDecidedThroughThisParent(String action) throws Exception {
        Fixture f = new Fixture("read");
        Fixture other = new Fixture("read");
        var before = f.stored();
        var otherBefore = other.stored();
        call(DEMO_OWNER, post(f.path() + "/" + other.correction + "/" + action), Map.of("note", "Wrong parent"))
            .andExpect(status().isNotFound());
        assertEquals(before, f.stored());
        assertEquals(otherBefore, other.stored());
    }

    @Test
    void approvingAgainIsAConflictAndLeavesTheAppliedAuditRowsIntact() throws Exception {
        Fixture f = new Fixture("approve");
        data(f.action("approve", DEMO_OWNER));
        f.assertAppliedOnce();
        var before = f.stored();
        f.action("approve", DEMO_OWNER).andExpect(status().isConflict());
        assertEquals(before, f.stored());
    }

    @Test
    void simultaneousApprovalsApplyOnlyOnce() throws Exception {
        Fixture f = new Fixture("approve");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(20, TimeUnit.SECONDS));
                return f.action("approve", DEMO_OWNER).andReturn().getResponse().getStatus();
            });
            var second = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(20, TimeUnit.SECONDS));
                return f.action("approve", DEMO_OWNER).andReturn().getResponse().getStatus();
            });
            assertTrue(ready.await(15, TimeUnit.SECONDS));
            start.countDown();
            var statuses = new ArrayList<>(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)));
            Collections.sort(statuses);
            assertEquals(List.of(200, 409), statuses);
        } finally {
            start.countDown();
        }
        f.assertAppliedOnce();
    }

    @Test
    void rejectionKeepsWorkedTimeAndCannotBeApprovedLater() throws Exception {
        Fixture f = new Fixture("reject");
        var laborBefore = f.laborRows();
        assertEquals("rejected", data(f.action("reject", DEMO_OWNER)).path("status").asText());
        assertEquals(laborBefore, f.laborRows());
        var before = f.stored();
        f.action("approve", DEMO_OWNER).andExpect(status().isConflict());
        assertEquals(before, f.stored());
    }

    private final class Fixture {
        final String project;
        final String run;
        final String line;
        String correction;

        Fixture(String action) throws Exception {
            project = data(call(DEMO_OWNER, post("/projects"), Map.of("ownerId", DEMO_OWNER,
                "projectName", "Labor corrections " + UUID.randomUUID()))).path("projectId").asText();
            String workflow = data(call(DEMO_OWNER, post("/workflows"), Map.of("projectId", project,
                "workflowName", "Labor"))).path("workflowId").asText();
            JsonNode started = data(call(DEMO_OWNER, post("/production-runs/start"), Map.of("projectId", project,
                "workflowId", workflow, "runType", "simulation", "plannedOutputQty", 1)));
            assertTrue(started.path("bomId").isNull());
            run = started.path("productionRunId").asText();
            line = data(call(DEMO_OWNER, post("/production-runs/" + run + "/labor"),
                Map.of("requestId", UUID.randomUUID(), "workedMinutes", 17))).path("lines").get(0).path("runLaborId").asText();
            call(DEMO_OWNER, post("/production-runs/" + run + "/finish"), Map.of("actualOutputQty", 1))
                .andExpect(status().isOk());
            if (!"request".equals(action)) request(DEMO_OWNER);
        }

        String path() { return "/production-runs/" + run + "/corrections"; }

        Map<String, Object> body() {
            return Map.of("reason", "Correct measured time", "lines", List.of(
                Map.of("kind", "cancel_labor", "targetRunLaborId", line),
                Map.of("kind", "add_labor", "userId", DEMO_OWNER, "workedMinutes", 7)));
        }

        JsonNode request(String actor) throws Exception {
            JsonNode requested = data(call(actor, post(path()), body()));
            correction = requested.path("productionRunCorrectionId").asText();
            return requested;
        }

        MockHttpServletRequestBuilder requestFor(String action) throws Exception {
            return switch (action) {
                case "read" -> get(path());
                case "request" -> post(path()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body()));
                case "approve" -> post(path() + "/" + correction + "/approve");
                case "reject" -> post(path() + "/" + correction + "/reject").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"note\":\"Not needed\"}");
                default -> throw new IllegalArgumentException(action);
            };
        }

        ResultActions action(String action, String actor) throws Exception {
            return call(actor, requestFor(action), null);
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

        List<Map<String, Object>> laborRows() {
            return jdbc.queryForList("select * from production_run_labor where production_run_id=? order by run_labor_id", run);
        }

        Map<String, List<Map<String, Object>>> stored() {
            return Map.of("run", jdbc.queryForList("select * from production_run where production_run_id=?", run),
                "labor", laborRows(),
                "corrections", jdbc.queryForList("select * from production_run_correction where production_run_id=? "
                    + "order by production_run_correction_id", run),
                "lines", jdbc.queryForList("select l.* from production_run_correction_line l join production_run_correction c "
                    + "on l.production_run_correction_id=c.production_run_correction_id where c.production_run_id=? "
                    + "order by l.production_run_correction_line_id", run));
        }

        void assertAppliedOnce() throws Exception {
            JsonNode labor = data(call(DEMO_OWNER, get("/production-runs/" + run + "/labor"), null));
            assertEquals(7, labor.path("workedMinutes").asInt());
            assertEquals(2, labor.path("lines").size());
            int cancelled = 0;
            int added = 0;
            for (JsonNode row : labor.path("lines")) {
                if (line.equals(row.path("runLaborId").asText())) {
                    assertTrue(row.path("cancelled").asBoolean());
                    assertEquals(17, row.path("workedMinutes").asInt());
                    assertEquals(DEMO_OWNER, row.path("cancelledBy").asText());
                    cancelled++;
                } else {
                    assertEquals(7, row.path("workedMinutes").asInt());
                    assertEquals(DEMO_OWNER, row.path("recordedBy").asText());
                    added++;
                }
            }
            assertEquals(1, cancelled);
            assertEquals(1, added);
            JsonNode saved = data(action("read", DEMO_OWNER));
            assertEquals(1, saved.size());
            assertEquals("applied", saved.get(0).path("status").asText());
            assertEquals(2, saved.get(0).path("lines").size());
            assertTrue(!saved.get(0).path("lines").get(1).path("createdRunLaborId").asText().isBlank());
        }
    }

    private ResultActions call(String actor, MockHttpServletRequestBuilder request, Object body) throws Exception {
        if (actor != null) request.header("Authorization", "Bearer " + jwt.generateAccessToken(actor));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        return mvc.perform(request);
    }

    private JsonNode data(ResultActions response) throws Exception {
        return mapper.readTree(response.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
}
