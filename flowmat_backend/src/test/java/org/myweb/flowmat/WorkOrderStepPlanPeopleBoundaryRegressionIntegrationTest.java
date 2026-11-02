package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Exercises LB29 through the real authenticated HTTP API. Each test owns its user, project, workflow and order.
 * SQL inserts fixture users and project members only; all state changes and observations use public APIs.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Timeout(120)
class WorkOrderStepPlanPeopleBoundaryRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void aRemovedPersonsStoredPlanCanBeReadButCannotBeResubmittedAndCanBeReplacedOrCleared() throws Exception {
        Fixture fixture = fixture();
        Member removed = member(fixture.project());
        List<Map<String, Object>> originalPeople = List.of(person(fixture.owner(), 40), person(removed.user(), 60));
        Map<String, Object> original = splitRequest(fixture.node(), null, originalPeople);
        JsonNode before = ok(save(fixture, original));
        assertSingleSplit(fixture, before, 100, originalPeople);

        ok(call(fixture.owner(), delete("/project-members/" + removed.membership()), null));
        JsonNode activeMembers = ok(call(fixture.owner(), get("/project-members").param("projectId", fixture.project()), null));
        assertTrue(activeMembers.isArray());
        for (JsonNode one : activeMembers) assertNotEquals(removed.user(), one.path("userId").asText());
        assertPreserved(fixture, before);

        Reply refused = save(fixture, original);
        assertEquals(400, refused.status(), refused.message());
        assertTrue(refused.message().contains(removed.user()), refused.message());
        assertTrue(refused.message().contains("not in this project"), refused.message());
        assertPreserved(fixture, before);

        List<Map<String, Object>> remaining = List.of(person(fixture.owner(), 40));
        JsonNode changed = ok(save(fixture, splitRequest(fixture.node(), null, remaining)));
        assertSingleSplit(fixture, changed, 40, remaining);
        assertPreserved(fixture, changed);

        JsonNode cleared = ok(save(fixture, Map.of("steps", List.of())));
        assertTrue(cleared.path("stepPlannedMinutes").isNull());
        assertTrue(cleared.path("steps").isArray());
        assertEquals(0, cleared.path("steps").size());
        assertPreserved(fixture, cleared);
    }

    @Test
    void fiftyPeopleAndOneHundredThousandMinutesAreAcceptedButOveragesPreserveThePlan() throws Exception {
        Fixture fixture = fixture();
        List<Member> members = new ArrayList<>();
        for (int index = 0; index < 51; index++) members.add(member(fixture.project()));
        List<Map<String, Object>> fifty = new ArrayList<>();
        for (int index = 0; index < 50; index++) fifty.add(person(members.get(index).user(), 2_000));
        JsonNode before = ok(save(fixture, splitRequest(fixture.node(), 100_000, fifty)));
        assertSingleSplit(fixture, before, 100_000, fifty);
        assertPreserved(fixture, before);

        List<Map<String, Object>> fiftyOne = new ArrayList<>();
        for (Member member : members) fiftyOne.add(person(member.user(), 1));
        Reply tooMany = save(fixture, splitRequest(fixture.node(), null, fiftyOne));
        assertEquals(400, tooMany.status(), tooMany.message());
        assertTrue(tooMany.message().contains("at most 50 people"), tooMany.message());
        assertPreserved(fixture, before);

        List<Map<String, Object>> tooManyMinutes = new ArrayList<>(fifty);
        tooManyMinutes.set(49, person(members.get(49).user(), 2_001));
        Reply excessiveTotal = save(fixture, splitRequest(fixture.node(), null, tooManyMinutes));
        assertEquals(400, excessiveTotal.status(), excessiveTotal.message());
        assertTrue(excessiveTotal.message().contains("more than 100000"), excessiveTotal.message());
        assertPreserved(fixture, before);
    }

    @Test
    void fractionalStepAndPersonMinutesAreRefusedWhileExactIntegerRepresentationsRemainAccepted() throws Exception {
        Fixture fixture = fixture();
        List<Map<String, Object>> originalPeople = List.of(person(fixture.owner(), 60));
        JsonNode before = ok(save(fixture, splitRequest(fixture.node(), 60, originalPeople)));
        assertSingleSplit(fixture, before, 60, originalPeople);

        for (Object fraction : List.of(2.5, "2.5")) {
            // No people on this request: accidental truncation to 2 must not be hidden by a mismatching step total.
            Reply stepFraction = save(fixture, Map.of("steps", List.of(Map.of(
                "nodeId", fixture.node(), "plannedMinutes", fraction))));
            assertEquals(400, stepFraction.status(), stepFraction.message());
            assertPreserved(fixture, before);

            // Leave the step total out, so the person's exact-number validation is the only numeric gate.
            Reply personFraction = save(fixture, splitRequest(fixture.node(), null,
                List.of(person(fixture.owner(), fraction))));
            assertEquals(400, personFraction.status(), personFraction.message());
            assertPreserved(fixture, before);
        }

        JsonNode integerStrings = ok(save(fixture, splitRequest(fixture.node(), "30",
            List.of(person(fixture.owner(), "30")))));
        assertSingleSplit(fixture, integerStrings, 30, List.of(person(fixture.owner(), 30)));
        assertPreserved(fixture, integerStrings);

        JsonNode exactDecimals = ok(save(fixture, splitRequest(fixture.node(), 1.0,
            List.of(person(fixture.owner(), 1.0)))));
        assertSingleSplit(fixture, exactDecimals, 1, List.of(person(fixture.owner(), 1)));
        assertPreserved(fixture, exactDecimals);
    }

    @Test
    void deletingAProcessPreservesItsStoredSplitPlanAndPreventsResavingTheDeletedNode() throws Exception {
        Fixture fixture = fixture();
        Member other = member(fixture.project());
        List<Map<String, Object>> people = List.of(person(fixture.owner(), 30), person(other.user(), 70));
        Map<String, Object> request = splitRequest(fixture.node(), null, people);
        JsonNode before = ok(save(fixture, request));
        assertSingleSplit(fixture, before, 100, people);

        ok(call(fixture.owner(), delete("/processes/" + fixture.node()), null));
        Reply deleted = call(fixture.owner(), get("/processes/" + fixture.node()), null);
        assertEquals(404, deleted.status(), deleted.message());
        JsonNode afterDeletion = comparison(fixture);
        assertEquals(before, afterDeletion);
        assertSingleSplit(fixture, afterDeletion, 100, people);

        Reply refused = save(fixture, request);
        assertEquals(400, refused.status(), refused.message());
        assertTrue(refused.message().contains("not a process"), refused.message());
        assertPreserved(fixture, before);
    }

    private Fixture fixture() throws Exception {
        String owner = user();
        String tag = UUID.randomUUID().toString().substring(0, 12);
        String project = ok(call(owner, post("/projects"), Map.of("projectName", "People boundaries " + tag, "ownerId", owner)))
            .path("projectId").asText();
        String workflow = ok(call(owner, post("/workflows"), Map.of("projectId", project, "workflowName", "People plan " + tag)))
            .path("workflowId").asText();
        String nodeName = "Preserved process " + tag;
        String node = ok(call(owner, post("/processes"), Map.of("workflowId", workflow, "processName", nodeName,
            "nodeType", "process"))).path("processId").asText();
        JsonNode order = ok(call(owner, post("/work-orders"), Map.of("projectId", project, "workflowId", workflow,
            "workOrderTitle", "People boundaries " + tag, "targetQuantity", 1)));
        assertEquals("draft", order.path("workOrderStatus").asText());
        return new Fixture(owner, project, node, nodeName, order.path("workOrderId").asText());
    }

    private String user() {
        String user = UUID.randomUUID().toString();
        assertEquals(1, jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel)"
            + " select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?",
            user, user + "@test.local", DEMO_OWNER));
        return user;
    }

    private Member member(String project) {
        String user = user();
        String membership = UUID.randomUUID().toString();
        assertEquals(1, jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role,member_status)"
            + " values(?,?,?,'editor','active')", membership, project, user));
        return new Member(user, membership);
    }

    private static Map<String, Object> person(String user, Object minutes) {
        return Map.of("userId", user, "plannedMinutes", minutes);
    }

    private static Map<String, Object> splitRequest(String node, Object minutes, List<Map<String, Object>> people) {
        Map<String, Object> step = new HashMap<>();
        step.put("nodeId", node);
        if (minutes != null) step.put("plannedMinutes", minutes);
        step.put("people", people);
        return Map.of("steps", List.of(step));
    }

    private Reply save(Fixture fixture, Object body) throws Exception {
        return call(fixture.owner(), put(fixture.path() + "/step-plan"), body);
    }

    private JsonNode comparison(Fixture fixture) throws Exception {
        return ok(call(fixture.owner(), get(fixture.path() + "/labor-comparison"), null));
    }

    private void assertPreserved(Fixture fixture, JsonNode before) throws Exception {
        assertEquals(before, comparison(fixture), "The public comparison must preserve the stored process and person plan.");
    }

    private static void assertSingleSplit(Fixture fixture, JsonNode comparison, int total,
                                         List<Map<String, Object>> expectedPeople) {
        assertEquals(total, comparison.path("stepPlannedMinutes").asInt());
        assertTrue(comparison.path("steps").isArray());
        assertEquals(1, comparison.path("steps").size());
        JsonNode step = comparison.path("steps").get(0);
        assertEquals(fixture.node(), step.path("nodeId").asText());
        assertEquals(fixture.nodeName(), step.path("nodeName").asText());
        assertEquals(total, step.path("plannedMinutes").asInt());
        assertEquals(0, step.path("actualMinutes").asInt());
        assertTrue(step.path("people").isArray());
        assertEquals(expectedPeople.size(), step.path("people").size());
        for (int index = 0; index < expectedPeople.size(); index++) {
            Map<String, Object> expected = expectedPeople.get(index);
            JsonNode actual = step.path("people").get(index);
            assertEquals(expected.get("userId"), actual.path("userId").asText());
            assertEquals(((Number) expected.get("plannedMinutes")).intValue(), actual.path("plannedMinutes").asInt());
            assertEquals(0, actual.path("actualMinutes").asInt());
        }
    }

    private Reply call(String owner, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(owner));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        var response = mvc.perform(request).andReturn().getResponse();
        JsonNode envelope = json.readTree(response.getContentAsString());
        return new Reply(response.getStatus(), envelope.path("data"), envelope.path("message").asText());
    }

    private static JsonNode ok(Reply reply) {
        assertEquals(200, reply.status(), reply.message());
        return reply.data();
    }

    private record Fixture(String owner, String project, String node, String nodeName, String order) {
        String path() { return "/work-orders/" + order; }
    }

    private record Member(String user, String membership) {}

    private record Reply(int status, JsonNode data, String message) {}
}
