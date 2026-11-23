package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Approvals told both ways (docs/domain/notification.md NT8, user decision 2026-10-13): the project owner hears of a submitted
 * BOM revision and a requested run correction, and the person who asked hears the decision. Against real Postgres.
 */
@AutoConfigureMockMvc
class ApprovalNotificationIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void theOwnerHearsOfACorrectionRequestAndTheRequesterOfTheDecision() throws Exception {
        String editor = member();
        String run = data(callAs(DEMO_OWNER, post("/production-runs/start"), json(Map.of("projectId", DEMO_PROJECT,
            "workflowId", DEMO_WORKFLOW, "plannedOutputQty", 1)))).path("productionRunId").asText();
        callAs(DEMO_OWNER, post("/production-runs/" + run + "/finish"), json(Map.of("actualOutputQty", 1))).andExpect(status().isOk());
        String runNumber = data(callAs(DEMO_OWNER, get("/production-runs/" + run), null)).path("runNumber").asText();

        String first = correction(editor, run, "Counted one short", 2);
        JsonNode asked = only(DEMO_OWNER, run);
        assertEquals("correction_approval", asked.path("type").asText());
        assertEquals("Correction #1 of " + runNumber + " waits for your approval", asked.path("title").asText());
        assertEquals("Counted one short", asked.path("body").asText());
        assertEquals("production_run", asked.path("refType").asText());
        assertEquals(DEMO_PROJECT, asked.path("projectId").asText());
        // The requester is not told of their own request.
        assertEquals(0, told(editor, run).size());

        callAs(DEMO_OWNER, post(corrections(run) + "/" + first + "/reject"), json(Map.of("note", "Recount first")))
            .andExpect(status().isOk());
        JsonNode rejected = only(editor, run);
        assertEquals("correction_decision", rejected.path("type").asText());
        assertEquals("Correction #1 of " + runNumber + " was rejected", rejected.path("title").asText());
        assertEquals("Recount first", rejected.path("body").asText());

        String second = correction(editor, run, "Recounted: two", 2);
        callAs(DEMO_OWNER, post(corrections(run) + "/" + second + "/approve"), json(Map.of())).andExpect(status().isOk());
        List<JsonNode> editorHeard = told(editor, run);
        assertEquals(2, editorHeard.size());
        assertEquals("Correction #2 of " + runNumber + " was approved", editorHeard.get(0).path("title").asText());
        assertEquals("Its changes are applied.", editorHeard.get(0).path("body").asText());
        assertEquals(2, told(DEMO_OWNER, run).size());

        // The owner asking for and deciding a correction themselves tells no one.
        String own = correction(DEMO_OWNER, run, "Owner fix", 3);
        callAs(DEMO_OWNER, post(corrections(run) + "/" + own + "/approve"), json(Map.of())).andExpect(status().isOk());
        assertEquals(2, told(DEMO_OWNER, run).size());
        assertEquals(2, told(editor, run).size());
    }

    @Test
    void theOwnerHearsOfASubmittedBomAndTheSubmitterOfTheDecision() throws Exception {
        String editor = member();
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String bread = item("ANB-" + tag, "unit_ea");
        String flour = item("ANF-" + tag, "unit_kg");
        String bom = data(callAs(editor, post("/boms"), json(Map.of("projectId", DEMO_PROJECT, "targetItemId", bread,
            "bomName", "Bread " + tag, "baseQuantity", 1, "baseUnit", "ea")))).path("bomId").asText();
        callAs(editor, post("/boms/" + bom + "/lines"), json(Map.of("childItemId", flour, "quantity", 1, "unit", "kg")))
            .andExpect(status().isOk());

        callAs(editor, post("/boms/" + bom + "/submit"), null).andExpect(status().isOk());
        JsonNode asked = only(DEMO_OWNER, bom);
        assertEquals("bom_approval", asked.path("type").asText());
        assertEquals("BOM Bread " + tag + " v1 waits for your approval", asked.path("title").asText());
        assertEquals("Submitted by " + editor + ".", asked.path("body").asText());
        assertEquals("bom", asked.path("refType").asText());

        callAs(DEMO_OWNER, post("/boms/" + bom + "/reject"), json(Map.of("note", "Flour is per ten"))).andExpect(status().isOk());
        JsonNode rejected = only(editor, bom);
        assertEquals("bom_decision", rejected.path("type").asText());
        assertEquals("BOM Bread " + tag + " v1 was rejected", rejected.path("title").asText());
        assertEquals("Flour is per ten", rejected.path("body").asText());

        callAs(editor, post("/boms/" + bom + "/submit"), null).andExpect(status().isOk());
        callAs(DEMO_OWNER, post("/boms/" + bom + "/approve"), json(Map.of())).andExpect(status().isOk());
        List<JsonNode> editorHeard = told(editor, bom);
        assertEquals(2, editorHeard.size());
        assertEquals("BOM Bread " + tag + " v1 was approved", editorHeard.get(0).path("title").asText());
        assertEquals("Approved.", editorHeard.get(0).path("body").asText());
        assertEquals(2, told(DEMO_OWNER, bom).size());
    }

    private String correction(String userId, String run, String reason, int afterQty) throws Exception {
        return data(callAs(userId, post(corrections(run)), json(Map.of("reason", reason,
            "lines", List.of(Map.of("kind", "set_output_qty", "afterQty", afterQty)))))).path("productionRunCorrectionId").asText();
    }

    private static String corrections(String run) {
        return "/production-runs/" + run + "/corrections";
    }

    /** The user's notifications about {@code refId}, newest first. */
    private List<JsonNode> told(String userId, String refId) throws Exception {
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode notification : data(callAs(userId, get("/notifications"), null))) {
            if (refId.equals(notification.path("refId").asText())) {
                found.add(notification);
            }
        }
        return found;
    }

    private JsonNode only(String userId, String refId) throws Exception {
        List<JsonNode> found = told(userId, refId);
        assertEquals(1, found.size(), "notifications of " + userId + " about " + refId);
        return found.get(0);
    }

    private String item(String code, String unitId) throws Exception {
        return data(callAs(DEMO_OWNER, post("/items"), json(Map.of("projectId", DEMO_PROJECT, "itemCode", code,
            "itemName", code.toLowerCase(), "unitId", unitId)))).path("itemId").asText();
    }

    private String member() {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
            UUID.randomUUID().toString(), DEMO_PROJECT, id, "editor");
        return id;
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private ResultActions callAs(String userId, MockHttpServletRequestBuilder request, String body) throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(userId)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
}
