package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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

/** What a supplier credited for stock sent back is kept once per return (docs/domain/purchasing.md PO47), in a project of its own. */
@AutoConfigureMockMvc
class PurchaseCreditIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void aReturnIsCreditedOnceWithTheSuppliersNote() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String project = call(DEMO_OWNER, post("/projects"), Map.of("projectName", "Credits " + tag, "ownerId", DEMO_OWNER), 200)
            .path("projectId").asText();
        String flour = call(DEMO_OWNER, post("/items"), Map.of("projectId", project, "itemCode", "CR-FLOUR-" + tag, "itemName", "flour",
            "itemType", "material", "unitId", "unit_kg"), 200).path("itemId").asText();

        // Flour 10 at 2.5 came; 4 go back for a credit and 2 to be replaced.
        JsonNode first = ordered(project, flour);
        String orderId = first.path("purchaseOrderId").asText();
        String path = "/purchase-orders/" + orderId;
        String line = first.path("lines").get(0).path("lineId").asText();
        JsonNode received = call(DEMO_OWNER, post(path + "/receipts"), Map.of("requestId", UUID.randomUUID().toString(),
            "lines", List.of(Map.of("lineId", line, "quantity", 10, "location", "WH-A"))), 200);
        String stock = received.path("receipts").get(0).path("inventoryId").asText();
        call(DEMO_OWNER, post(path + "/returns"), back(line, stock, "4", false), 200);
        JsonNode returned = call(DEMO_OWNER, post(path + "/returns"), back(line, stock, "2", true), 200);
        String wet = returned.path("returns").get(0).path("returnId").asText();
        String replaced = returned.path("returns").get(1).path("returnId").asText();
        assertTrue(returned.path("returns").get(0).path("creditAmount").isNull(), returned.toString());

        // An amount of 0 or more with at most four decimals, on a return of this order.
        call(DEMO_OWNER, post(path + "/returns/" + wet + "/credit"), credit("-1", null), 400);
        call(DEMO_OWNER, post(path + "/returns/" + wet + "/credit"), credit("1.00001", null), 400);
        call(DEMO_OWNER, post(path + "/returns/" + wet + "/credit"), credit(null, "CN-12"), 400);
        call(DEMO_OWNER, post(path + "/returns/" + wet + "/credit"), null, 400);
        call(DEMO_OWNER, post(path + "/returns/missing/credit"), credit("10", null), 404);
        String other = "/purchase-orders/" + ordered(project, flour).path("purchaseOrderId").asText();
        call(DEMO_OWNER, post(other + "/returns/" + wet + "/credit"), credit("10", null), 404);
        call(member(project, "viewer"), post(path + "/returns/" + wet + "/credit"), credit("10", null), 403);

        JsonNode credited = call(DEMO_OWNER, post(path + "/returns/" + wet + "/credit"), credit("10", " CN-12 "), 200);
        JsonNode one = credited.path("returns").get(0);
        assertValue("10", one.path("creditAmount"));
        assertEquals("CN-12", one.path("creditNote").asText());
        assertEquals(DEMO_OWNER, one.path("creditedBy").asText());
        assertFalse(one.path("creditedAt").isNull(), one.toString());
        assertTrue(credited.path("returns").get(1).path("creditAmount").isNull(), credited.toString());

        // The same credit again changes nothing; another one is refused.
        assertValue("10", call(DEMO_OWNER, post(path + "/returns/" + wet + "/credit"), credit("10.0", "CN-12"), 200)
            .path("returns").get(0).path("creditAmount"));
        call(DEMO_OWNER, post(path + "/returns/" + wet + "/credit"), credit("9", "CN-12"), 409);
        call(DEMO_OWNER, post(path + "/returns/" + wet + "/credit"), credit("10", "CN-13"), 409);

        // A replacement that will not come is credited too, here without a note.
        call(DEMO_OWNER, post(path + "/close"), Map.of("reason", "Supplier stopped"), 200);
        JsonNode closed = call(DEMO_OWNER, post(path + "/returns/" + replaced + "/credit"), credit("5", null), 200);
        assertValue("5", closed.path("returns").get(1).path("creditAmount"));
        assertTrue(closed.path("returns").get(1).path("creditNote").isNull(), closed.toString());
        assertEquals(2, jdbc.queryForObject("select count(*) from purchase_credit where purchase_order_id = ?", Integer.class, orderId));
    }

    /** A new order of flour 10 at 2.5 from Mill Co, ordered. */
    private JsonNode ordered(String projectId, String itemId) throws Exception {
        JsonNode draft = call(DEMO_OWNER, post("/purchase-orders"), Map.of("projectId", projectId, "supplierName", "Mill Co",
            "lines", List.of(Map.of("itemId", itemId, "quantity", 10, "unitPrice", new BigDecimal("2.5")))), 200);
        return call(DEMO_OWNER, post("/purchase-orders/" + draft.path("purchaseOrderId").asText() + "/order"), null, 200);
    }

    private static Map<String, Object> back(String lineId, String inventoryId, String quantity, boolean replacement) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("lineId", lineId);
        body.put("inventoryId", inventoryId);
        body.put("quantity", new BigDecimal(quantity));
        body.put("reason", "Wet bags");
        body.put("replacement", replacement);
        return body;
    }

    private static Map<String, Object> credit(String amount, String creditNote) {
        Map<String, Object> body = new HashMap<>();
        body.put("amount", amount == null ? null : new BigDecimal(amount));
        body.put("creditNote", creditNote);
        return body;
    }

    private static void assertValue(String expected, JsonNode actual) {
        assertTrue(actual.isNumber(), String.valueOf(actual));
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()), actual.toString());
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
