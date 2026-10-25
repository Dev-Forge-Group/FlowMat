package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

/** Stock received on a purchase order going back to the supplier (docs/domain/purchasing.md PO26-PO28), in a project of its own. */
@AutoConfigureMockMvc
class PurchaseReturnIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void receivedStockGoesBackToTheSupplierAsAnAdjustmentEvenWhenQuarantined() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String project = call(DEMO_OWNER, post("/projects"), Map.of("projectName", "Returns " + tag, "ownerId", DEMO_OWNER), 200)
            .path("projectId").asText();
        String flour = item(project, "PR-FLOUR-" + tag, "N");
        String yeast = item(project, "PR-YEAST-" + tag, "Y");

        // PO-0001 brings flour 10 and yeast LOT L-1; PO-0002 brings yeast LOT L-9.
        JsonNode first = received(project, List.of(Map.of("itemId", flour, "quantity", 10), Map.of("itemId", yeast, "quantity", 5)),
            List.of(receiving(0, 10, null), receiving(1, 5, "L-1")));
        JsonNode second = received(project, List.of(Map.of("itemId", yeast, "quantity", 3)), List.of(receiving(0, 3, "L-9")));
        String path = "/purchase-orders/" + first.path("purchaseOrderId").asText();
        String flourLine = first.path("lines").get(0).path("lineId").asText();
        String yeastLine = first.path("lines").get(1).path("lineId").asText();
        String flourStock = first.path("receipts").get(0).path("inventoryId").asText();
        String yeastStock = first.path("receipts").get(1).path("inventoryId").asText();
        String otherYeast = second.path("receipts").get(0).path("inventoryId").asText();

        // Nothing received, nothing goes back.
        JsonNode draft = call(DEMO_OWNER, post("/purchase-orders"), Map.of("projectId", project, "supplierName", "Mill Co",
            "lines", List.of(Map.of("itemId", flour, "quantity", 1))), 200);
        String draftLine = draft.path("lines").get(0).path("lineId").asText();
        call(DEMO_OWNER, post("/purchase-orders/" + draft.path("purchaseOrderId").asText() + "/returns"),
            back(draftLine, flourStock, "1", "Wet bags"), 409);

        // A reason, a line of the order, at most what came, from a record of the item; a LOT item as a LOT that came on the line.
        call(DEMO_OWNER, post(path + "/returns"), back(flourLine, flourStock, "4", " "), 400);
        call(DEMO_OWNER, post(path + "/returns"), back(draftLine, flourStock, "4", "Wet bags"), 400);
        call(DEMO_OWNER, post(path + "/returns"), back(flourLine, flourStock, "11", "Wet bags"), 400);
        call(DEMO_OWNER, post(path + "/returns"), back(flourLine, yeastStock, "4", "Wet bags"), 400);
        call(DEMO_OWNER, post(path + "/returns"), back(flourLine, "missing", "4", "Wet bags"), 400);
        call(DEMO_OWNER, post(path + "/returns"), back(yeastLine, otherYeast, "1", "Dead yeast"), 400);

        Map<String, Object> wet = back(flourLine, flourStock, "4", "Wet bags");
        JsonNode returned = call(DEMO_OWNER, post(path + "/returns"), wet, 200);
        assertEquals(1, returned.path("returns").size(), returned.toString());
        JsonNode one = returned.path("returns").get(0);
        assertValue("4", one.path("quantity"));
        assertEquals("WH-A", one.path("location").asText());
        assertTrue(one.path("lotNo").isNull(), one.toString());
        assertEquals("Wet bags", one.path("reason").asText());
        assertEquals("PR-FLOUR-" + tag, one.path("itemCode").asText());
        assertValue("4", returned.path("lines").get(0).path("returnedQuantity"));
        assertValue("10", returned.path("lines").get(0).path("receivedQuantity"));
        assertEquals("received", returned.path("status").asText());
        assertValue("6", call(DEMO_OWNER, get("/inventories/" + flourStock), null, 200).path("quantity"));

        // The same request again changes nothing; the same key for another return is refused.
        assertEquals(1, call(DEMO_OWNER, post(path + "/returns"), wet, 200).path("returns").size());
        Map<String, Object> other = new HashMap<>(wet);
        other.put("quantity", new BigDecimal("2"));
        call(DEMO_OWNER, post(path + "/returns"), other, 409);
        call(DEMO_OWNER, post(path + "/returns"), back(flourLine, flourStock, "7", "Wet bags"), 400);

        // Reserved stock stays; quarantined stock goes back.
        call(DEMO_OWNER, post("/inventory-transactions"), Map.of("inventoryId", flourStock, "transactionType", "reserve", "quantity", 5,
            "requestId", UUID.randomUUID().toString()), 200);
        call(DEMO_OWNER, post(path + "/returns"), back(flourLine, flourStock, "2", "Wet bags"), 409);
        call(DEMO_OWNER, post("/inventory-transactions"), Map.of("inventoryId", yeastStock, "transactionType", "quarantine",
            "requestId", UUID.randomUUID().toString()), 200);
        JsonNode dead = call(DEMO_OWNER, post(path + "/returns"), back(yeastLine, yeastStock, "2", "Dead yeast"), 200);
        assertEquals("L-1", dead.path("returns").get(1).path("lotNo").asText());
        assertValue("3", call(DEMO_OWNER, get("/inventories/" + yeastStock), null, 200).path("quantity"));

        // Readers see the returns; only writers send stock back.
        String viewer = member(project, "viewer");
        assertEquals(2, call(viewer, get(path), null, 200).path("returns").size());
        call(viewer, post(path + "/returns"), back(yeastLine, yeastStock, "1", "Viewer"), 403);
    }

    /** A new order of these lines, ordered and received as given. */
    private JsonNode received(String projectId, List<Map<String, Object>> lines, List<Map<String, Object>> receipts) throws Exception {
        JsonNode draft = call(DEMO_OWNER, post("/purchase-orders"), Map.of("projectId", projectId, "supplierName", "Mill Co", "lines", lines), 200);
        String path = "/purchase-orders/" + draft.path("purchaseOrderId").asText();
        call(DEMO_OWNER, post(path + "/order"), null, 200);
        List<Map<String, Object>> withLines = receipts.stream().map(receipt -> {
            Map<String, Object> line = new HashMap<>(receipt);
            line.put("lineId", draft.path("lines").get((Integer) line.remove("index")).path("lineId").asText());
            return line;
        }).toList();
        return call(DEMO_OWNER, post(path + "/receipts"), Map.of("requestId", UUID.randomUUID().toString(), "lines", withLines), 200);
    }

    private static Map<String, Object> receiving(int index, int quantity, String lotNo) {
        Map<String, Object> line = new HashMap<>();
        line.put("index", index);
        line.put("quantity", quantity);
        line.put("location", "WH-A");
        if (lotNo != null) {
            line.put("lotNo", lotNo);
            line.put("expiryDate", "2042-01-01");
        }
        return line;
    }

    private static Map<String, Object> back(String lineId, String inventoryId, String quantity, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("lineId", lineId);
        body.put("inventoryId", inventoryId);
        body.put("quantity", new BigDecimal(quantity));
        body.put("reason", reason);
        return body;
    }

    private static void assertValue(String expected, JsonNode actual) {
        assertTrue(actual.isNumber(), String.valueOf(actual));
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()), actual.toString());
    }

    private String item(String projectId, String code, String lotManageYn) throws Exception {
        return call(DEMO_OWNER, post("/items"), Map.of("projectId", projectId, "itemCode", code, "itemName", code.toLowerCase(),
            "itemType", "material", "unitId", "unit_kg", "lotManageYn", lotManageYn), 200).path("itemId").asText();
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
