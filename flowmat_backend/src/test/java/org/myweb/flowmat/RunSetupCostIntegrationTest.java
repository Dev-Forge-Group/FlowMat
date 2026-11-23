package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.project.domain.entity.ProjectMember;
import org.myweb.flowmat.domain.project.repository.ProjectMemberRepository;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Actual run setups with the rate snapshot (docs/domain/equipment-setup-cost.md AS1-AS6) against real Postgres. */
@AutoConfigureMockMvc
class RunSetupCostIntegrationTest extends IntegrationTestSupport {

    private static final String VIEWER = "run-setup-viewer";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ProjectMemberRepository projectMemberRepository;

    @Test
    void aSetupKeepsTheRateItWasRecordedAtAndStaysApartFromMaterialCost() throws Exception {
        String press = equipment("Setup press");
        rate(press, 30, 0);
        String order = data(call(post("/work-orders"), json(Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "workOrderTitle", "Setup order", "targetQuantity", 1)))).path("workOrderId").asText();
        call(put("/work-orders/" + order + "/equipment"), json(Map.of("equipmentId", press))).andExpect(status().isOk());
        call(post("/work-orders/" + order + "/approve")).andExpect(status().isOk());
        String run = data(call(post("/production-runs/start"), json(Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "workOrderId", order, "plannedOutputQty", 1)))).path("productionRunId").asText();
        call(get(setups(run)))
            .andExpect(jsonPath("$.data.defaultEquipmentId").value(press))
            .andExpect(jsonPath("$.data.setupMinutes").value(0))
            .andExpect(jsonPath("$.data.setupCost").value(0))
            .andExpect(jsonPath("$.data.costComplete").value(true));

        // 45 minutes at 30 an hour on the work order's press (AS1, AS2).
        Map<String, Object> first = setup(null, 45, "Die change");
        call(post(setups(run)), json(first))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lines[0].equipmentId").value(press))
            .andExpect(jsonPath("$.data.lines[0].hourlyCost").value(30))
            .andExpect(jsonPath("$.data.lines[0].hourlyCostVersion").value(1))
            .andExpect(jsonPath("$.data.lines[0].setupCost").value(22.5))
            .andExpect(jsonPath("$.data.setupCost").value(22.5));

        // A later rate counts for later setups only.
        rate(press, 60, 1);
        String second = data(call(post(setups(run)), json(setup(press, 15, null)))).path("lines").get(1).path("runSetupId").asText();
        call(get(setups(run)))
            .andExpect(jsonPath("$.data.lines[0].setupCost").value(22.5))
            .andExpect(jsonPath("$.data.lines[1].setupCost").value(15))
            .andExpect(jsonPath("$.data.setupMinutes").value(60))
            .andExpect(jsonPath("$.data.setupCost").value(37.5));

        // The same setup again is answered with the run as it is; different content under its key is refused (AS3).
        call(post(setups(run)), json(first)).andExpect(status().isOk()).andExpect(jsonPath("$.data.lines.length()").value(2));
        Map<String, Object> changed = new HashMap<>(first);
        changed.put("setupMinutes", 50);
        call(post(setups(run)), json(changed)).andExpect(status().isConflict());

        // Equipment without a rate: known subtotal only (AS5); material cost does not include setups.
        String bare = equipment("Bare bench");
        call(post(setups(run)), json(setup(bare, 10, null)))
            .andExpect(jsonPath("$.data.lines[2].setupCost").isEmpty())
            .andExpect(jsonPath("$.data.costComplete").value(false))
            .andExpect(jsonPath("$.data.setupCost").value(37.5));
        call(get("/production-runs/" + run + "/cost")).andExpect(jsonPath("$.data.materialCost").value(0));

        // Cancelling needs a reason, keeps the row, and stops it counting (AS4).
        call(post(setups(run) + "/" + second + "/cancel"), json(Map.of())).andExpect(status().isBadRequest());
        call(post(setups(run) + "/" + second + "/cancel"), json(Map.of("reason", "Recorded twice")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lines[1].cancelled").value(true))
            .andExpect(jsonPath("$.data.lines[1].cancelReason").value("Recorded twice"))
            .andExpect(jsonPath("$.data.setupMinutes").value(55))
            .andExpect(jsonPath("$.data.setupCost").value(22.5));

        // Reading is Project read, recording is write; outsiders see nothing.
        ensureMember(VIEWER, "viewer");
        callAs(VIEWER, get(setups(run))).andExpect(status().isOk());
        callAs(VIEWER, post(setups(run)), json(setup(press, 5, null))).andExpect(status().isForbidden());
        callAs("run-setup-outsider", get(setups(run))).andExpect(status().isForbidden());

        // A finished run's setups are fixed.
        call(post("/production-runs/" + run + "/finish"), json(Map.of("actualOutputQty", 1))).andExpect(status().isOk());
        call(post(setups(run)), json(setup(press, 5, null)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("already finished")));
        call(get(setups(run))).andExpect(jsonPath("$.data.lines.length()").value(3));
    }

    @Test
    void aSetupNeedsItsEquipmentAndSaneInput() throws Exception {
        String run = data(call(post("/production-runs/start"), json(Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "plannedOutputQty", 1)))).path("productionRunId").asText();
        call(post(setups(run)), json(setup(null, 10, null)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("Pick the equipment")));
        String press = equipment("Input press");
        call(post(setups(run)), json(setup(press, 0, null))).andExpect(status().isBadRequest());
        call(post(setups(run)), json(setup(press, 1441, null))).andExpect(status().isBadRequest());
        call(post(setups(run)), json(setup("no-such-equipment", 10, null))).andExpect(status().isBadRequest());
        call(post(setups(run)), json(setup(press, 10, "x".repeat(501)))).andExpect(status().isBadRequest());
        Map<String, Object> noKey = setup(press, 10, null);
        noKey.remove("requestId");
        call(post(setups(run)), json(noKey)).andExpect(status().isBadRequest());
        call(get(setups(run))).andExpect(jsonPath("$.data.lines.length()").value(0));
        call(get(setups("no-such-run"))).andExpect(status().isNotFound());
    }

    @Test
    void anOrdersPlannedChangeoverStandsBesideItsRunsActualSetups() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String press = equipment("Compare press " + tag);
        rate(press, 30, 0);
        String red = data(call(post("/items"), json(Map.of("projectId", DEMO_PROJECT, "itemCode", "CMP-RED-" + tag,
            "itemName", "red", "itemType", "product", "unitId", "unit_ea")))).path("itemId").asText();
        String white = data(call(post("/items"), json(Map.of("projectId", DEMO_PROJECT, "itemCode", "CMP-WHITE-" + tag,
            "itemName", "white", "itemType", "product", "unitId", "unit_ea")))).path("itemId").asText();
        Map<String, Object> rule = new HashMap<>();
        rule.put("fromItemId", null);
        rule.put("toItemId", null);
        rule.put("minutes", 20);
        call(post("/equipments/" + press + "/changeovers"), json(rule)).andExpect(status().isOk());
        String first = order("Compare first", red, "2030-03-01T00:00:00Z", "2030-03-01T08:00:00Z", press);
        String second = order("Compare second", white, "2030-03-02T00:00:00Z", "2030-03-02T08:00:00Z", press);
        // Only planned work is the order before it on the load board, so the first one is approved (EquipmentSequence).
        call(post("/work-orders/" + first + "/approve")).andExpect(status().isOk());

        // Before any run: 20 planned minutes at 30 an hour, nothing actual yet (AS11).
        call(get("/work-orders/" + second + "/setup-comparison"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.plannedMinutes").value(20))
            .andExpect(jsonPath("$.data.plannedCost").value(10.0))
            .andExpect(jsonPath("$.data.runs").value(0))
            .andExpect(jsonPath("$.data.actualMinutes").value(0));
        call(get("/work-orders/" + first + "/setup-comparison")).andExpect(jsonPath("$.data.plannedMinutes").isEmpty());

        call(post("/work-orders/" + second + "/approve")).andExpect(status().isOk());
        String run = data(call(post("/production-runs/start"), json(Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "workOrderId", second, "plannedOutputQty", 1, "runType", "actual")))).path("productionRunId").asText();
        call(post(setups(run)), json(setup(null, 45, "Longer clean"))).andExpect(status().isOk());
        call(get("/work-orders/" + second + "/setup-comparison"))
            .andExpect(jsonPath("$.data.runs").value(1))
            .andExpect(jsonPath("$.data.actualMinutes").value(45))
            .andExpect(jsonPath("$.data.actualCost").value(22.5))
            .andExpect(jsonPath("$.data.actualCostComplete").value(true));
    }

    private String order(String title, String itemId, String start, String end, String equipmentId) throws Exception {
        String orderId = data(call(post("/work-orders"), json(Map.of("projectId", DEMO_PROJECT, "workOrderTitle", title,
            "workflowId", DEMO_WORKFLOW, "targetItemId", itemId, "targetQuantity", 1, "plannedStartAt", start,
            "plannedEndAt", end)))).path("workOrderId").asText();
        call(put("/work-orders/" + orderId + "/equipment"), json(Map.of("equipmentId", equipmentId))).andExpect(status().isOk());
        return orderId;
    }

    private static String setups(String runId) {
        return "/production-runs/" + runId + "/setups";
    }

    private static Map<String, Object> setup(String equipmentId, int minutes, String note) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("setupMinutes", minutes);
        if (equipmentId != null) {
            body.put("equipmentId", equipmentId);
        }
        if (note != null) {
            body.put("note", note);
        }
        return body;
    }

    private String equipment(String name) throws Exception {
        return data(call(post("/equipments"), json(Map.of("projectId", DEMO_PROJECT, "equipmentName", name, "equipmentType", "machine",
            "details", Map.of("capacityPerHour", 10))))).path("equipmentId").asText();
    }

    private void rate(String equipmentId, int hourlyCost, int expectedVersion) throws Exception {
        call(put("/equipments/" + equipmentId + "/hourly-cost"), json(Map.of("hourlyCost", hourlyCost, "expectedVersion", expectedVersion)))
            .andExpect(status().isOk());
    }

    private void ensureMember(String userId, String role) {
        if (projectMemberRepository.existsByProjectIdAndUserIdAndMemberStatus(DEMO_PROJECT, userId, "active")) {
            return;
        }
        ProjectMember member = new ProjectMember();
        member.setProjectMemberId("pm-setup-" + UUID.randomUUID().toString().substring(0, 8));
        member.setProjectId(DEMO_PROJECT);
        member.setUserId(userId);
        member.setProjectRole(role);
        member.setMemberStatus("active");
        member.setInvitedBy(DEMO_OWNER);
        member.setJoinedAt(OffsetDateTime.now());
        projectMemberRepository.save(member);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return callAs(DEMO_OWNER, request);
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return callAs(DEMO_OWNER, request, body);
    }

    private ResultActions callAs(String user, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(user)));
    }

    private ResultActions callAs(String user, MockHttpServletRequestBuilder request, String body) throws Exception {
        return callAs(user, request.contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
