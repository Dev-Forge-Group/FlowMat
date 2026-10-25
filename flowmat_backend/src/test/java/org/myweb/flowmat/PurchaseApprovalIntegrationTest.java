package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The project owner's approval of purchase orders from a limit up (docs/domain/purchasing.md PO33-PO35), in a project of its own. */
@AutoConfigureMockMvc
class PurchaseApprovalIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void ordersFromTheLimitUpWaitForTheOwnersApproval() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String project = call(DEMO_OWNER, post("/projects"), Map.of("projectName", "Approval " + tag, "ownerId", DEMO_OWNER), 200)
            .path("projectId").asText();
        String flour = call(DEMO_OWNER, post("/items"), Map.of("projectId", project, "itemCode", "PA-FLOUR-" + tag, "itemName", "flour",
            "itemType", "material", "unitId", "unit_kg"), 200).path("itemId").asText();
        String editor = member(project, "editor");
        String viewer = member(project, "viewer");

        // Without a limit nothing needs approval.
        JsonNode none = call(viewer, get("/purchase-settings").param("projectId", project), null, 200);
        assertTrue(none.path("approvalLimit").isNull(), none.toString());
        assertTrue(none.path("version").isNull(), none.toString());
        JsonNode big = call(editor, post("/purchase-orders"), order(project, flour, "10", "20"), 200);
        assertFalse(big.path("needsApproval").asBoolean());

        // Only the owner sets the limit, from the version it opened.
        call(editor, put("/purchase-settings"), setting(project, "100", null), 403);
        call(DEMO_OWNER, put("/purchase-settings"), setting(project, "-1", null), 400);
        assertEquals(0, call(DEMO_OWNER, put("/purchase-settings"), setting(project, "100", null), 200).path("version").asLong());
        assertEquals(0, call(DEMO_OWNER, put("/purchase-settings"), setting(project, "100.00", null), 200).path("version").asLong());
        call(DEMO_OWNER, put("/purchase-settings"), setting(project, "150", null), 409);

        // 200 is from the limit up: the editor cannot order or approve it; the owner approves, then it is ordered.
        String bigPath = "/purchase-orders/" + big.path("purchaseOrderId").asText();
        assertTrue(call(editor, get(bigPath), null, 200).path("needsApproval").asBoolean());
        call(editor, post(bigPath + "/order"), null, 409);
        call(editor, post(bigPath + "/approve"), null, 403);
        JsonNode approved = call(DEMO_OWNER, post(bigPath + "/approve"), null, 200);
        assertEquals(DEMO_OWNER, approved.path("approvedBy").asText());
        assertFalse(approved.path("approvedAt").isNull());
        assertEquals("ordered", call(editor, post(bigPath + "/order"), null, 200).path("status").asText());
        call(DEMO_OWNER, post(bigPath + "/approve"), null, 409);
        // Saved before the limit, it asked no one; its author hears of the approval (PO36).
        assertEquals(0, told(DEMO_OWNER, big.path("purchaseOrderId").asText()).size());
        List<JsonNode> heard = told(editor, big.path("purchaseOrderId").asText());
        assertEquals(1, heard.size(), heard.toString());
        assertEquals("purchase_approved", heard.get(0).path("type").asText());
        assertEquals(big.path("orderNumber").asText() + " was approved", heard.get(0).path("title").asText());

        // Under the limit it is ordered at once; a line without a price needs approval, and a change takes the approval away.
        JsonNode small = call(editor, post("/purchase-orders"), order(project, flour, "1", "20"), 200);
        assertFalse(small.path("needsApproval").asBoolean());
        call(editor, post("/purchase-orders/" + small.path("purchaseOrderId").asText() + "/order"), null, 200);
        JsonNode unpriced = call(editor, post("/purchase-orders"), order(project, flour, "1", null), 200);
        assertTrue(unpriced.path("needsApproval").asBoolean());
        // The owner is told about a saved draft that waits for approval.
        List<JsonNode> asked = told(DEMO_OWNER, unpriced.path("purchaseOrderId").asText());
        assertEquals(1, asked.size(), asked.toString());
        assertEquals("purchase_approval", asked.get(0).path("type").asText());
        assertEquals(unpriced.path("orderNumber").asText() + " needs your approval", asked.get(0).path("title").asText());
        assertEquals("Mill Co: 1 line not priced yet.", asked.get(0).path("body").asText());
        assertEquals("purchase_order", asked.get(0).path("refType").asText());
        String unpricedPath = "/purchase-orders/" + unpriced.path("purchaseOrderId").asText();
        call(DEMO_OWNER, post(unpricedPath + "/approve"), null, 200);
        JsonNode changed = call(editor, put(unpricedPath), order(project, flour, "2", null), 200);
        assertTrue(changed.path("approvedBy").isNull(), changed.toString());
        call(editor, post(unpricedPath + "/order"), null, 409);
        // Changed, it asks again; the owner's own drafts ask no one.
        assertEquals(2, told(DEMO_OWNER, unpriced.path("purchaseOrderId").asText()).size());
        JsonNode own = call(DEMO_OWNER, post("/purchase-orders"), order(project, flour, "10", "20"), 200);
        assertEquals(0, told(DEMO_OWNER, own.path("purchaseOrderId").asText()).size());

        // Without the limit again, it is ordered.
        assertEquals(1, call(DEMO_OWNER, put("/purchase-settings"), setting(project, null, 0L), 200).path("version").asLong());
        assertEquals("ordered", call(editor, post(unpricedPath + "/order"), null, 200).path("status").asText());
    }

    /** The user's notifications about {@code refId}, newest first. */
    private List<JsonNode> told(String userId, String refId) throws Exception {
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode notification : call(userId, get("/notifications"), null, 200)) {
            if (refId.equals(notification.path("refId").asText())) {
                found.add(notification);
            }
        }
        return found;
    }

    private static Map<String, Object> setting(String projectId, String limit, Long expectedVersion) {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", projectId);
        body.put("approvalLimit", limit == null ? null : new BigDecimal(limit));
        body.put("expectedVersion", expectedVersion);
        return body;
    }

    private static Map<String, Object> order(String projectId, String itemId, String quantity, String price) {
        Map<String, Object> line = new HashMap<>();
        line.put("itemId", itemId);
        line.put("quantity", new BigDecimal(quantity));
        if (price != null) line.put("unitPrice", new BigDecimal(price));
        return Map.of("projectId", projectId, "supplierName", "Mill Co", "lines", List.of(line));
    }

    /** A new member of a project. */
    private String member(String projectId, String role) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
            UUID.randomUUID().toString(), projectId, id, role);
        return id;
    }

    private JsonNode call(String user, MockHttpServletRequestBuilder request, Object body, int expected) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(user));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        var response = mvc.perform(request).andReturn().getResponse();
        JsonNode parsed = mapper.readTree(response.getContentAsString());
        assertEquals(expected, response.getStatus(), parsed.path("message").asText());
        return parsed.path("data");
    }
}
