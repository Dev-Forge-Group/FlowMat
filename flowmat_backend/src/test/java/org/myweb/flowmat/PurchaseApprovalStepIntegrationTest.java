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
import java.time.OffsetDateTime;
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

/** Members approve purchase orders from their step's value up, in turn (docs/domain/purchasing.md PO50-PO51), in a project of its own. */
@AutoConfigureMockMvc
class PurchaseApprovalStepIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void membersApproveFromTheirStepUpInTurnBeforeTheOrder() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String project = call(DEMO_OWNER, post("/projects"), Map.of("projectName", "Approval steps " + tag, "ownerId", DEMO_OWNER), 200)
            .path("projectId").asText();
        String flour = call(DEMO_OWNER, post("/items"), Map.of("projectId", project, "itemCode", "AS-FLOUR-" + tag, "itemName", "flour",
            "itemType", "material", "unitId", "unit_kg"), 200).path("itemId").asText();
        String kim = member(project, "editor");
        String lee = member(project, "editor");
        String viewer = member(project, "viewer");

        // Steps are by members who can edit, each once and not the owner, from 0 or more; only the owner sets them.
        call(DEMO_OWNER, put("/purchase-settings"), setting(project, "1000", null, List.of(step("100", viewer))), 400);
        call(DEMO_OWNER, put("/purchase-settings"), setting(project, "1000", null, List.of(step("100", DEMO_OWNER))), 400);
        call(DEMO_OWNER, put("/purchase-settings"), setting(project, "1000", null, List.of(step("100", kim), step("200", kim))), 400);
        call(DEMO_OWNER, put("/purchase-settings"), setting(project, "1000", null, List.of(step("-1", kim))), 400);
        call(kim, put("/purchase-settings"), setting(project, "1000", null, List.of(step("100", kim))), 403);
        JsonNode saved = call(DEMO_OWNER, put("/purchase-settings"), setting(project, "1000", null, List.of(step("500", lee), step("100", kim))), 200);
        assertEquals(List.of(kim, lee), approvers(saved.path("approvalSteps")));
        assertTrue(saved.path("approvalSteps").get(0).path("canApprove").asBoolean());
        long version = saved.path("version").asLong();
        // The limit sent alone keeps the steps; the same again changes nothing.
        JsonNode limitOnly = call(DEMO_OWNER, put("/purchase-settings"), setting(project, "1000.0", version, null), 200);
        assertEquals(version, limitOnly.path("version").asLong());
        assertEquals(List.of(kim, lee), approvers(limitOnly.path("approvalSteps")));

        // Worth 600: kim, then lee; the owner's limit is not reached.
        JsonNode draft = call(DEMO_OWNER, post("/purchase-orders"), order(project, flour, "10", "60"), 200);
        String id = draft.path("purchaseOrderId").asText();
        String path = "/purchase-orders/" + id;
        assertTrue(draft.path("needsApproval").asBoolean());
        assertEquals(List.of(kim, lee), approvers(draft.path("approvals")));
        assertTrue(draft.path("approvals").get(0).path("approvedAt").isNull(), draft.toString());
        assertEquals(1, told(kim, id, "purchase_approval"));
        assertEquals(0, told(lee, id, "purchase_approval"));
        call(lee, post(path + "/approve"), null, 409);
        call(viewer, post(path + "/approve"), null, 403);
        call(DEMO_OWNER, post(path + "/approve"), null, 403);
        call(DEMO_OWNER, post(path + "/order"), null, 409);
        JsonNode first = call(kim, post(path + "/approve"), null, 200);
        assertFalse(first.path("approvals").get(0).path("approvedAt").isNull(), first.toString());
        assertTrue(first.path("approvedAt").isNull(), first.toString());
        assertEquals(1, told(lee, id, "purchase_approval"));
        assertEquals(OffsetDateTime.parse(first.path("approvals").get(0).path("approvedAt").asText()).toInstant(),
            OffsetDateTime.parse(call(kim, post(path + "/approve"), null, 200).path("approvals").get(0).path("approvedAt").asText()).toInstant());
        JsonNode last = call(lee, post(path + "/approve"), null, 200);
        assertEquals(lee, last.path("approvedBy").asText());
        assertFalse(last.path("approvedAt").isNull(), last.toString());
        assertEquals(1, told(DEMO_OWNER, id, "purchase_approved"));
        JsonNode ordered = call(kim, post(path + "/order"), null, 200);
        assertEquals("ordered", ordered.path("status").asText());
        assertEquals(List.of(kim, lee), approvers(ordered.path("approvals")));

        // Not priced yet, it waits for every step and the owner; a change takes the approvals away.
        JsonNode unpriced = call(DEMO_OWNER, post("/purchase-orders"), order(project, flour, "1", null), 200);
        String unpricedPath = "/purchase-orders/" + unpriced.path("purchaseOrderId").asText();
        assertEquals(List.of(kim, lee, DEMO_OWNER), approvers(unpriced.path("approvals")));
        assertTrue(unpriced.path("approvals").get(2).path("projectOwner").asBoolean(), unpriced.toString());
        call(kim, post(unpricedPath + "/approve"), null, 200);
        JsonNode changed = call(DEMO_OWNER, put(unpricedPath), order(project, flour, "2", null), 200);
        assertTrue(changed.path("approvals").get(0).path("approvedAt").isNull(), changed.toString());

        // Worth 50 waits for no one; only the owner may approve it then.
        JsonNode small = call(DEMO_OWNER, post("/purchase-orders"), order(project, flour, "1", "50"), 200);
        String smallPath = "/purchase-orders/" + small.path("purchaseOrderId").asText();
        assertFalse(small.path("needsApproval").asBoolean());
        call(kim, post(smallPath + "/approve"), null, 403);
        assertEquals(DEMO_OWNER, call(DEMO_OWNER, post(smallPath + "/approve"), null, 200).path("approvedBy").asText());

        // A step's member who can no longer edit is shown so; their step waits until it is changed.
        jdbc.update("update project_member set project_role='viewer' where project_id=? and user_id=?", project, lee);
        JsonNode read = call(DEMO_OWNER, get("/purchase-settings").param("projectId", project), null, 200);
        assertTrue(read.path("approvalSteps").get(0).path("canApprove").asBoolean());
        assertFalse(read.path("approvalSteps").get(1).path("canApprove").asBoolean());
    }

    private static List<String> approvers(JsonNode list) {
        List<String> found = new ArrayList<>();
        list.forEach(one -> found.add(one.path("approverId").asText()));
        return found;
    }

    /** How many notifications of the type the user has about refId. */
    private int told(String userId, String refId, String type) throws Exception {
        int found = 0;
        for (JsonNode notification : call(userId, get("/notifications"), null, 200)) {
            if (refId.equals(notification.path("refId").asText()) && type.equals(notification.path("type").asText())) {
                found++;
            }
        }
        return found;
    }

    private static Map<String, Object> step(String fromValue, String approverId) {
        return Map.of("fromValue", new BigDecimal(fromValue), "approverId", approverId);
    }

    private static Map<String, Object> setting(String projectId, String limit, Long expectedVersion, List<Map<String, Object>> steps) {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", projectId);
        body.put("approvalLimit", limit == null ? null : new BigDecimal(limit));
        body.put("expectedVersion", expectedVersion);
        if (steps != null) body.put("approvalSteps", steps);
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
