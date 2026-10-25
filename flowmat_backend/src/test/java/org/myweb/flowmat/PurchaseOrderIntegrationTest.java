package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Arrays;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Purchase orders and their receipts (docs/architecture/adr/ADR-027-purchasing.md), on real Postgres in a project of their own. */
@AutoConfigureMockMvc
class PurchaseOrderIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void aPurchaseOrderIsDraftedOrderedReceivedIntoStockAndClosed() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String project = data(DEMO_OWNER, post("/projects"), Map.of("projectName", "Purchasing " + tag, "ownerId", DEMO_OWNER))
            .path("projectId").asText();
        String flour = item(project, "PO-FLOUR-" + tag, "N");
        String yeast = item(project, "PO-YEAST-" + tag, "Y");

        // Refused before anything is saved.
        call(DEMO_OWNER, post("/purchase-orders"), order(project, " ", line(flour, "10"))).andExpect(status().isBadRequest());
        call(DEMO_OWNER, post("/purchase-orders"), order(project, "Mill", line(flour, "10"), line(flour, "2"))).andExpect(status().isBadRequest());
        call(DEMO_OWNER, post("/purchase-orders"), order(project, "Mill", line(flour, "0"))).andExpect(status().isBadRequest());
        call(DEMO_OWNER, post("/purchase-orders"), order(project, "Mill", line(flour, "1.00001"))).andExpect(status().isBadRequest());
        call(DEMO_OWNER, post("/purchase-orders"), order(project, "Mill")).andExpect(status().isBadRequest());
        call(DEMO_OWNER, post("/purchase-orders"), order(DEMO_PROJECT, "Mill", line(flour, "1"))).andExpect(status().isBadRequest());
        call(DEMO_OWNER, post("/purchase-orders"), "{\"projectId\":\"" + project + "\",\"supplierName\":\"Mill\",\"lines\":"
            + "[{\"itemId\":\"" + flour + "\",\"quantity\":\"ten\"}]}").andExpect(status().isBadRequest());

        JsonNode draft = data(DEMO_OWNER, post("/purchase-orders"), order(project, "Mill Co", line(flour, "10")));
        assertEquals("PO-0001", draft.path("orderNumber").asText());
        assertEquals("draft", draft.path("status").asText());
        String path = "/purchase-orders/" + draft.path("purchaseOrderId").asText();

        // A draft is replaced whole; once ordered its lines are fixed.
        JsonNode edited = data(DEMO_OWNER, put(path), order(project, "Mill Co", line(flour, "10"), line(yeast, "2")));
        assertEquals(2, edited.path("lines").size());
        assertTrue(edited.path("lines").get(1).path("lotTracked").asBoolean(), edited.toString());
        JsonNode ordered = data(DEMO_OWNER, post(path + "/order"), null);
        assertEquals("ordered", ordered.path("status").asText());
        call(DEMO_OWNER, put(path), order(project, "Mill Co", line(flour, "1"))).andExpect(status().isConflict());
        call(DEMO_OWNER, post(path + "/order"), null).andExpect(status().isConflict());
        String flourLine = ordered.path("lines").get(0).path("lineId").asText();
        String yeastLine = ordered.path("lines").get(1).path("lineId").asText();

        // More than ordered, or a LOT item without its LOT, is refused whole.
        call(DEMO_OWNER, post(path + "/receipts"), receipt(UUID.randomUUID(), arrived(flourLine, "11", "WH-A", null, null)))
            .andExpect(status().isBadRequest());
        call(DEMO_OWNER, post(path + "/receipts"), receipt(UUID.randomUUID(), arrived(flourLine, "4", "WH-A", null, null),
            arrived(yeastLine, "2", null, null, null))).andExpect(status().isBadRequest());
        assertEquals(0, data(DEMO_OWNER, get(path), null).path("receipts").size());

        UUID first = UUID.randomUUID();
        Map<String, Object> body = receipt(first, arrived(flourLine, "4", "WH-A", null, null),
            arrived(yeastLine, "2", null, "L-" + tag, "2041-12-31"));
        JsonNode partly = data(DEMO_OWNER, post(path + "/receipts"), body);
        assertEquals("ordered", partly.path("status").asText());
        assertEquals(0, new BigDecimal("4").compareTo(partly.path("lines").get(0).path("receivedQuantity").decimalValue()));
        assertEquals(0, new BigDecimal("6").compareTo(partly.path("lines").get(0).path("remainingQuantity").decimalValue()));
        assertEquals(2, partly.path("receipts").size());
        // The same request again changes nothing; other lines under its key are refused.
        assertEquals(2, data(DEMO_OWNER, post(path + "/receipts"), body).path("receipts").size());
        call(DEMO_OWNER, post(path + "/receipts"), receipt(first, arrived(flourLine, "1", "WH-A", null, null))).andExpect(status().isConflict());

        // The stock is in the ledger under the purchase receipt; a new LOT was registered for the yeast.
        String flourStock = partly.path("receipts").get(0).path("inventoryId").asText();
        JsonNode row = data(DEMO_OWNER, get("/inventories/" + flourStock), null);
        assertEquals(0, new BigDecimal("4").compareTo(row.path("quantity").decimalValue()));
        assertEquals("WH-A", row.path("location").asText());
        String ledger = data(DEMO_OWNER, get("/inventory-transactions").param("inventoryId", flourStock), null).toString();
        assertTrue(ledger.contains("purchase_receipt") && ledger.contains("Received on PO-0001 from Mill Co"), ledger);
        String yeastStock = partly.path("receipts").get(1).path("inventoryId").asText();
        assertTrue(data(DEMO_OWNER, get("/inventories/" + yeastStock), null).path("lotId").asText().length() > 0);

        // The rest of the flour joins the same record; with everything in, the order is received.
        JsonNode done = data(DEMO_OWNER, post(path + "/receipts"), receipt(UUID.randomUUID(), arrived(flourLine, "6", "WH-A", null, null)));
        assertEquals("received", done.path("status").asText());
        assertEquals(flourStock, done.path("receipts").get(2).path("inventoryId").asText());
        assertEquals(0, new BigDecimal("10").compareTo(data(DEMO_OWNER, get("/inventories/" + flourStock), null).path("quantity").decimalValue()));
        call(DEMO_OWNER, post(path + "/receipts"), receipt(UUID.randomUUID(), arrived(flourLine, "1", "WH-A", null, null)))
            .andExpect(status().isConflict());
        call(DEMO_OWNER, post(path + "/close"), Map.of("reason", "x")).andExpect(status().isConflict());

        // A draft is cancelled with a reason; once something is received the rest is closed instead.
        JsonNode second = data(DEMO_OWNER, post("/purchase-orders"), order(project, "Salt Ltd", line(flour, "5")));
        assertEquals("PO-0002", second.path("orderNumber").asText());
        String secondPath = "/purchase-orders/" + second.path("purchaseOrderId").asText();
        call(DEMO_OWNER, post(secondPath + "/cancel"), Map.of()).andExpect(status().isBadRequest());
        call(DEMO_OWNER, post(secondPath + "/close"), Map.of("reason", "x")).andExpect(status().isConflict());
        JsonNode secondOrdered = data(DEMO_OWNER, post(secondPath + "/order"), null);
        data(DEMO_OWNER, post(secondPath + "/receipts"), receipt(UUID.randomUUID(),
            arrived(secondOrdered.path("lines").get(0).path("lineId").asText(), "2", "WH-A", null, null)));
        call(DEMO_OWNER, post(secondPath + "/cancel"), Map.of("reason", "x")).andExpect(status().isConflict());
        JsonNode closed = data(DEMO_OWNER, post(secondPath + "/close"), Map.of("reason", "Rest not coming"));
        assertEquals("closed", closed.path("status").asText());
        assertEquals("Rest not coming", closed.path("endReason").asText());
        JsonNode third = data(DEMO_OWNER, post("/purchase-orders"), order(project, "Oil Co", line(flour, "1")));
        assertEquals("cancelled", data(DEMO_OWNER, post("/purchase-orders/" + third.path("purchaseOrderId").asText() + "/cancel"),
            Map.of("reason", "Wrong supplier")).path("status").asText());

        // A viewer reads but does not write; an outsider does neither.
        String viewer = member(project, "viewer");
        assertEquals(3, data(viewer, get("/purchase-orders").param("projectId", project), null).size());
        assertEquals(1, data(viewer, get("/purchase-orders").param("projectId", project).param("status", "closed"), null).size());
        call(viewer, get("/purchase-orders").param("projectId", project).param("status", "lost"), null).andExpect(status().isBadRequest());
        call(viewer, post("/purchase-orders"), order(project, "X", line(flour, "1"))).andExpect(status().isForbidden());
        call("unrelated-user", get("/purchase-orders").param("projectId", project), null).andExpect(status().isForbidden());
        call("unrelated-user", get(path), null).andExpect(status().isForbidden());
    }

    private static Map<String, Object> order(String projectId, String supplier, Map<?, ?>... lines) {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", projectId);
        body.put("supplierName", supplier);
        body.put("lines", Arrays.asList(lines));
        return body;
    }

    private static Map<String, Object> line(String itemId, String quantity) {
        return Map.of("itemId", itemId, "quantity", new BigDecimal(quantity));
    }

    private static Map<String, Object> receipt(UUID requestId, Map<?, ?>... lines) {
        return Map.of("requestId", requestId.toString(), "lines", List.of(lines));
    }

    private static Map<String, Object> arrived(String lineId, String quantity, String location, String lotNo, String expiry) {
        Map<String, Object> line = new HashMap<>();
        line.put("lineId", lineId);
        line.put("quantity", new BigDecimal(quantity));
        if (location != null) line.put("location", location);
        if (lotNo != null) line.put("lotNo", lotNo);
        if (expiry != null) line.put("expiryDate", expiry);
        return line;
    }

    private String item(String projectId, String code, String lotTracked) throws Exception {
        return data(DEMO_OWNER, post("/items"), Map.of("projectId", projectId, "itemCode", code, "itemName", code.toLowerCase(),
            "itemType", "material", "unitId", "unit_kg", "lotManageYn", lotTracked)).path("itemId").asText();
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

    private JsonNode data(String user, MockHttpServletRequestBuilder request, Object body) throws Exception {
        var response = call(user, request, body).andReturn().getResponse();
        JsonNode parsed = mapper.readTree(response.getContentAsString());
        assertEquals(200, response.getStatus(), parsed.path("message").asText());
        return parsed.path("data");
    }

    private ResultActions call(String user, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(user));
        if (body instanceof String text) request.contentType(MediaType.APPLICATION_JSON).content(text);
        else if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        return mvc.perform(request);
    }
}
