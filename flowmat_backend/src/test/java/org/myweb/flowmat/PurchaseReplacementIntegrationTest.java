package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.myweb.flowmat.domain.purchasing.application.publicapi.PurchaseSupplyQuery;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Stock sent back for a replacement comes again on the same line (docs/domain/purchasing.md PO46), in a project of its own. */
@AutoConfigureMockMvc
class PurchaseReplacementIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private PurchaseSupplyQuery supply;

    @Test
    void aReplacementReopensTheLineUntilItHasCome() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String project = call(post("/projects"), Map.of("projectName", "Replacements " + tag, "ownerId", DEMO_OWNER), 200)
            .path("projectId").asText();
        String flour = call(post("/items"), Map.of("projectId", project, "itemCode", "RP-FLOUR-" + tag, "itemName", "flour",
            "itemType", "material", "unitId", "unit_kg"), 200).path("itemId").asText();

        // All 10 came; 4 go back to be replaced.
        JsonNode first = ordered(project, flour);
        String path = "/purchase-orders/" + first.path("purchaseOrderId").asText();
        String line = first.path("lines").get(0).path("lineId").asText();
        JsonNode received = receive(path, line, "10", 200);
        assertEquals("received", received.path("status").asText());
        String stock = received.path("receipts").get(0).path("inventoryId").asText();
        Map<String, Object> wet = back(line, stock, "4", "Wet bags", true);
        JsonNode reopened = call(post(path + "/returns"), wet, 200);
        assertEquals("ordered", reopened.path("status").asText());
        assertTrue(reopened.path("endedBy").isNull() && reopened.path("endedAt").isNull(), reopened.toString());
        JsonNode reopenedLine = reopened.path("lines").get(0);
        assertValue("10", reopenedLine.path("receivedQuantity"));
        assertValue("4", reopenedLine.path("replacementQuantity"));
        assertValue("4", reopenedLine.path("remainingQuantity"));
        assertTrue(reopened.path("returns").get(0).path("replacement").asBoolean(), reopened.toString());
        assertEquals(0, new BigDecimal("4").compareTo(supply.onOrder(project).get(flour)));

        // The same request again changes nothing; the same key without the replacement is another return.
        assertValue("4", call(post(path + "/returns"), wet, 200).path("lines").get(0).path("replacementQuantity"));
        Map<String, Object> credit = new HashMap<>(wet);
        credit.put("replacement", false);
        call(post(path + "/returns"), credit, 409);

        // Only the replacement is left to receive; once it came the order is received again and nothing is on order.
        receive(path, line, "5", 400);
        JsonNode replaced = receive(path, line, "4", 200);
        assertEquals("received", replaced.path("status").asText());
        assertValue("14", replaced.path("lines").get(0).path("receivedQuantity"));
        assertValue("0", replaced.path("lines").get(0).path("remainingQuantity"));
        assertNull(supply.onOrder(project).get(flour));

        // Sent back without a replacement, a received order stays received; it counts against what can go back.
        JsonNode kept = call(post(path + "/returns"), back(line, stock, "2", "Torn bag", false), 200);
        assertEquals("received", kept.path("status").asText());
        assertFalse(kept.path("returns").get(1).path("replacement").asBoolean(), kept.toString());
        assertValue("6", kept.path("lines").get(0).path("returnedQuantity"));
        assertValue("4", kept.path("lines").get(0).path("replacementQuantity"));
        assertValue("0", kept.path("lines").get(0).path("remainingQuantity"));
        call(post(path + "/returns"), back(line, stock, "9", "Too many", false), 400);

        // Partly received, a replacement adds to what is left; a closed order is not replaced on.
        JsonNode second = ordered(project, flour);
        String secondPath = "/purchase-orders/" + second.path("purchaseOrderId").asText();
        String secondLine = second.path("lines").get(0).path("lineId").asText();
        String secondStock = receive(secondPath, secondLine, "6", 200).path("receipts").get(0).path("inventoryId").asText();
        JsonNode partly = call(post(secondPath + "/returns"), back(secondLine, secondStock, "2", "Wet bags", true), 200);
        assertEquals("ordered", partly.path("status").asText());
        assertValue("6", partly.path("lines").get(0).path("remainingQuantity"));
        assertEquals(0, new BigDecimal("6").compareTo(supply.onOrder(project).get(flour)));
        call(post(secondPath + "/close"), Map.of("reason", "Supplier stopped"), 200);
        call(post(secondPath + "/returns"), back(secondLine, secondStock, "1", "Wet bags", true), 409);
        assertEquals("closed", call(post(secondPath + "/returns"), back(secondLine, secondStock, "1", "Wet bags", false), 200)
            .path("status").asText());
        assertNull(supply.onOrder(project).get(flour));
        assertEquals(2, call(get(secondPath), null, 200).path("returns").size());
    }

    /** A new order of flour 10 from Mill Co, ordered. */
    private JsonNode ordered(String projectId, String itemId) throws Exception {
        JsonNode draft = call(post("/purchase-orders"), Map.of("projectId", projectId, "supplierName", "Mill Co",
            "lines", List.of(Map.of("itemId", itemId, "quantity", 10))), 200);
        return call(post("/purchase-orders/" + draft.path("purchaseOrderId").asText() + "/order"), null, 200);
    }

    private JsonNode receive(String path, String lineId, String quantity, int expected) throws Exception {
        return call(post(path + "/receipts"), Map.of("requestId", UUID.randomUUID().toString(),
            "lines", List.of(Map.of("lineId", lineId, "quantity", new BigDecimal(quantity), "location", "WH-A"))), expected);
    }

    private static Map<String, Object> back(String lineId, String inventoryId, String quantity, String reason, boolean replacement) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("lineId", lineId);
        body.put("inventoryId", inventoryId);
        body.put("quantity", new BigDecimal(quantity));
        body.put("reason", reason);
        body.put("replacement", replacement);
        return body;
    }

    private static void assertValue(String expected, JsonNode actual) {
        assertTrue(actual.isNumber(), String.valueOf(actual));
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()), actual.toString());
    }

    private JsonNode call(MockHttpServletRequestBuilder request, Object body, int expected) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        var response = mvc.perform(request).andReturn().getResponse();
        JsonNode parsed = mapper.readTree(response.getContentAsString());
        assertEquals(expected, response.getStatus(), parsed.path("message").asText());
        return parsed.path("data");
    }
}
