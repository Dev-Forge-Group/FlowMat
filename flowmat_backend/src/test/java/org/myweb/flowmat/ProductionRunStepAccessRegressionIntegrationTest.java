package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.domain.project.domain.entity.ProjectMember;
import org.myweb.flowmat.domain.project.repository.ProjectMemberRepository;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Production step commands must retain project permissions and the run/step boundary (ADR-001, ADR-011). */
@AutoConfigureMockMvc
class ProductionRunStepAccessRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private ProjectMemberRepository members;

    @ParameterizedTest(name = "{0} cannot {1}")
    @CsvSource({"viewer,start", "viewer,complete", "viewer,fail", "outsider,list", "outsider,start", "outsider,complete", "outsider,fail"})
    void refusedRolesCannotReadOrChangeSteps(String role, String operation) throws Exception {
        Line line = new Line();
        String actor = "step-" + role + "-" + UUID.randomUUID();
        if ("viewer".equals(role)) line.member(actor, role);
        line.prepare(operation);
        JsonNode before = state(line);

        call(actor, command(line.runId, line.stepId, operation), body(operation))
            .andExpect(status().isForbidden());

        assertEquals(before, state(line), "A rejected request must not create attempts or routing events");
    }

    @ParameterizedTest
    @ValueSource(strings = {"list", "start", "complete", "fail"})
    void anonymousRequestsCannotReadOrChangeSteps(String operation) throws Exception {
        Line line = new Line();
        line.prepare(operation);
        JsonNode before = state(line);

        call(null, command(line.runId, line.stepId, operation), body(operation))
            .andExpect(status().isUnauthorized());

        assertEquals(before, state(line));
    }

    @ParameterizedTest
    @ValueSource(strings = {"start", "complete", "fail"})
    void aStepOfAnotherRunIsNotAcceptedEvenWhenTheCallerOwnsBoth(String operation) throws Exception {
        Line target = new Line();
        Line other = new Line();
        other.prepare(operation);
        JsonNode targetBefore = state(target);
        JsonNode otherBefore = state(other);

        call(DEMO_OWNER, command(target.runId, other.stepId, operation), body(operation))
            .andExpect(status().isNotFound());

        assertEquals(targetBefore, state(target));
        assertEquals(otherBefore, state(other), "An unrelated step must retain its attempts and output");
    }

    @Test
    void aViewerCanReadAndAnEditorCanCompleteButCannotReportTwice() throws Exception {
        Line line = new Line();
        String viewer = "step-viewer-" + UUID.randomUUID();
        String editor = "step-editor-" + UUID.randomUUID();
        line.member(viewer, "viewer");
        line.member(editor, "editor");
        call(viewer, command(line.runId, line.stepId, "list"), null).andExpect(status().isOk());
        call(editor, command(line.runId, line.stepId, "start"), null).andExpect(status().isOk());
        JsonNode completed = data(editor, command(line.runId, line.stepId, "complete"), body("complete"));
        assertEquals("completed", completed.path("status").asText());
        assertEquals(23, completed.path("outputSnapshot").path("quantity").asInt());
        JsonNode before = state(line);

        for (String operation : new String[] {"start", "complete", "fail"}) {
            call(editor, command(line.runId, line.stepId, operation), body(operation))
                .andExpect(status().isConflict());
            assertEquals(before, state(line), "A duplicate command must not add another attempt or completion event");
        }
        long completions = data(DEMO_OWNER, get("/flow-runs/" + line.flowRunId + "/events"), null)
            .findValuesAsText("eventType").stream().filter("step_completed"::equals).count();
        assertEquals(1, completions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"viewer", "editor"})
    void removingMembershipRevokesAccessDespiteAnUnexpiredToken(String role) throws Exception {
        Line line = new Line();
        String actor = "step-removed-" + UUID.randomUUID();
        ProjectMember member = line.member(actor, role);
        line.prepare("complete");
        JsonNode before = state(line);
        String accessToken = jwt.generateAccessToken(actor);
        mvc.perform(get("/production-runs/" + line.runId + "/steps")
            .header("Authorization", "Bearer " + accessToken)).andExpect(status().isOk());
        member.setMemberStatus("removed");
        members.saveAndFlush(member);

        mvc.perform(get("/production-runs/" + line.runId + "/steps")
            .header("Authorization", "Bearer " + accessToken)).andExpect(status().isForbidden());
        mvc.perform(post("/production-runs/" + line.runId + "/steps/" + line.stepId + "/complete")
            .header("Authorization", "Bearer " + accessToken).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(body("complete")))).andExpect(status().isForbidden());
        assertEquals(before, state(line));
    }

    @ParameterizedTest
    @ValueSource(strings = {"start", "complete", "fail"})
    void aFinishedProductionRunDoesNotAcceptStepChanges(String operation) throws Exception {
        Line line = new Line();
        data(DEMO_OWNER, post("/production-runs/" + line.runId + "/finish"), Map.of());
        JsonNode before = state(line);
        assertEquals("cancelled", before.path("steps").get(0).path("status").asText());

        call(DEMO_OWNER, command(line.runId, line.stepId, operation), body(operation))
            .andExpect(status().isConflict());

        assertEquals(before, state(line));
    }

    @ParameterizedTest
    @ValueSource(strings = {"start", "complete", "fail"})
    void theFlowRunApiCannotBypassProductionStepChecks(String operation) throws Exception {
        Line line = new Line();
        line.prepare(operation);
        JsonNode before = state(line);

        call(DEMO_OWNER, post("/flow-runs/" + line.flowRunId + "/steps/" + line.stepId + "/" + operation), body(operation))
            .andExpect(status().isConflict());

        assertEquals(before, state(line));
    }

    private JsonNode state(Line line) throws Exception {
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.set("steps", data(DEMO_OWNER, get("/production-runs/" + line.runId + "/steps"), null));
        snapshot.set("attempts", data(DEMO_OWNER, get("/flow-runs/" + line.flowRunId + "/steps/" + line.stepId + "/attempts"), null));
        snapshot.set("events", data(DEMO_OWNER, get("/flow-runs/" + line.flowRunId + "/events"), null));
        return snapshot;
    }

    private static MockHttpServletRequestBuilder command(String runId, String stepId, String operation) {
        String base = "/production-runs/" + runId + "/steps";
        return "list".equals(operation) ? get(base) : post(base + "/" + stepId + "/" + operation);
    }

    private static Object body(String operation) {
        return switch (operation) {
            case "complete" -> Map.of("outputSnapshot", Map.of("quantity", 23, "unit", "kg"));
            case "fail" -> Map.of("errorCode", "BLOCKED", "errorMessage", "Fixture failure");
            default -> null;
        };
    }

    private final class Line {
        final String projectId;
        final String runId;
        final String stepId;
        final String flowRunId;

        Line() throws Exception {
            projectId = data(DEMO_OWNER, post("/projects"), Map.of("projectName", "Step access " + UUID.randomUUID(),
                "ownerId", DEMO_OWNER)).path("projectId").asText();
            String workflow = data(DEMO_OWNER, post("/workflows"), Map.of("projectId", projectId, "workflowName", "One process"))
                .path("workflowId").asText();
            data(DEMO_OWNER, post("/processes"), Map.of("workflowId", workflow, "processName", "Mix", "nodeType", "process"));
            String revision = data(DEMO_OWNER, post("/workflows/" + workflow + "/revisions"), null).path("workflowRevisionId").asText();
            JsonNode run = data(DEMO_OWNER, post("/production-runs/start"), Map.of("projectId", projectId, "workflowId", workflow,
                "workflowRevisionId", revision, "plannedOutputQty", 100, "runType", "simulation"));
            assertTrue(run.path("bomId").isNull(), "The security fixture must not create or select a BOM");
            runId = run.path("productionRunId").asText();
            JsonNode steps = data(DEMO_OWNER, get("/production-runs/" + runId + "/steps"), null);
            assertEquals(1, steps.size());
            stepId = steps.get(0).path("stepId").asText();
            JsonNode flows = data(DEMO_OWNER, get("/flow-runs").param("workflowId", workflow), null);
            assertEquals(1, flows.size());
            flowRunId = flows.get(0).path("flowRunId").asText();
        }

        void prepare(String operation) throws Exception {
            if ("complete".equals(operation) || "fail".equals(operation)) {
                data(DEMO_OWNER, command(runId, stepId, "start"), null);
            }
        }

        ProjectMember member(String userId, String role) {
            ProjectMember member = new ProjectMember();
            member.setProjectMemberId(UUID.randomUUID().toString());
            member.setProjectId(projectId);
            member.setUserId(userId);
            member.setProjectRole(role);
            member.setMemberStatus("active");
            member.setInvitedBy(DEMO_OWNER);
            return members.saveAndFlush(member);
        }
    }

    private ResultActions call(String actor, MockHttpServletRequestBuilder request, Object body) throws Exception {
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        if (actor != null) request.header("Authorization", "Bearer " + jwt.generateAccessToken(actor));
        return mvc.perform(request);
    }

    private JsonNode data(String actor, MockHttpServletRequestBuilder request, Object body) throws Exception {
        var result = call(actor, request, body).andReturn();
        assertEquals(200, result.getResponse().getStatus(), "Fixture request failed: " + result.getRequest().getRequestURI());
        return mapper.readTree(result.getResponse().getContentAsString()).path("data");
    }
}
