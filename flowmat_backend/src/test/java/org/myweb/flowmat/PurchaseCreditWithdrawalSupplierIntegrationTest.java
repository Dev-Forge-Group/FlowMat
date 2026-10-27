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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * PO48 reads the project order list in the browser. Check that list after a committed withdrawal, rather than only the
 * mutation response. The supplier summary's arithmetic remains in the frontend; this test checks its real API inputs.
 */
@AutoConfigureMockMvc
class PurchaseCreditWithdrawalSupplierIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;

    @ParameterizedTest(name = "supplier summary inputs after withdrawal; replacement={0}")
    @ValueSource(booleans = {false, true})
    void aWithdrawalSurvivesTheOrderListReloadAndAnOriginalCreditRetry(boolean replacement) throws Exception {
        Fixture fixture = returnedOrder(replacement);
        Map<String, Object> original = Map.of("amount", "9.5000", "creditNote", "CN-ORIGINAL");
        ok(post(fixture.creditPath()), original);

        JsonNode credited = returned(listedOrder(fixture), fixture);
        assertDecimal("9.5000", credited.path("creditAmount"));
        assertEquals("9.5000", credited.path("creditAmountText").asText());
        assertTrue(credited.path("creditedAt").isTextual(), credited.toString());
        assertEquals(0, credited.path("creditChanges").size());

        ok(put(fixture.creditPath()), Map.of("amount", "8.0001", "creditNote", "CN-CHANGED",
            "reason", "Supplier revised the credit"));
        Map<String, Object> withdrawal = new HashMap<>();
        withdrawal.put("amount", null);
        withdrawal.put("creditNote", "MUST-NOT-SURVIVE-WITHDRAWAL");
        withdrawal.put("reason", "Supplier cancelled the credit");
        ok(put(fixture.creditPath()), withdrawal);

        JsonNode listed = listedOrder(fixture);
        assertSupplierSummaryInputs(fixture, listed);
        assertWithdrawn(returned(listed, fixture));
        JsonNode detail = ok(get(fixture.path()), null);
        assertEquals(listed.path("returns"), detail.path("returns"));
        assertEquals(listed.path("lines"), detail.path("lines"));

        // Retrying the first POST after its reply was lost must not resurrect the withdrawn credit in the list.
        ok(post(fixture.creditPath()), original);
        JsonNode retried = listedOrder(fixture);
        assertSupplierSummaryInputs(fixture, retried);
        assertWithdrawn(returned(retried, fixture));

        // A deliberate new credit changes the same persisted list again; the withdrawal history remains.
        ok(put(fixture.creditPath()), Map.of("amount", "10.0000", "creditNote", "CN-RESTORED",
            "reason", "Supplier issued the credit after all"));
        JsonNode restored = returned(listedOrder(fixture), fixture);
        assertDecimal("10.0000", restored.path("creditAmount"));
        assertEquals("10.0000", restored.path("creditAmountText").asText());
        assertEquals("CN-RESTORED", restored.path("creditNote").asText());
        assertTrue(restored.path("creditedAt").isTextual(), restored.toString());
        assertEquals(3, restored.path("creditChanges").size());
        assertNullFields(restored.path("creditChanges").get(1), "amount", "amountText", "creditNote");
    }

    private Fixture returnedOrder(boolean replacement) throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String project = ok(post("/projects"), Map.of("projectName", "Withdrawal supplier " + tag, "ownerId", DEMO_OWNER))
            .path("projectId").asText();
        String supplier = ok(post("/suppliers"), Map.of("projectId", project, "supplierName", "Credit supplier " + tag))
            .path("supplierName").asText();
        String item = ok(post("/items"), Map.of("projectId", project, "itemCode", "CWS-" + tag, "itemName", "flour",
            "itemType", "material", "unitId", "unit_kg")).path("itemId").asText();
        JsonNode draft = ok(post("/purchase-orders"), Map.of("projectId", project, "supplierName", supplier,
            "lines", List.of(Map.of("itemId", item, "quantity", 10, "unitPrice", new BigDecimal("2.5")))));
        String path = "/purchase-orders/" + draft.path("purchaseOrderId").asText();
        String line = ok(post(path + "/order"), null).path("lines").get(0).path("lineId").asText();
        String stock = ok(post(path + "/receipts"), Map.of("requestId", UUID.randomUUID().toString(),
            "lines", List.of(Map.of("lineId", line, "quantity", 10, "location", "WH-A"))))
            .path("receipts").get(0).path("inventoryId").asText();
        String back = ok(post(path + "/returns"), Map.of("requestId", UUID.randomUUID().toString(), "lineId", line,
            "inventoryId", stock, "quantity", 4, "replacement", replacement, "reason", "Wet bags"))
            .path("returns").get(0).path("returnId").asText();
        if (replacement) {
            ok(post(path + "/close"), Map.of("reason", "Supplier cannot replace the returned stock"));
        }
        return new Fixture(project, path, line, back, supplier, replacement);
    }

    private JsonNode listedOrder(Fixture fixture) throws Exception {
        JsonNode list = ok(get("/purchase-orders").param("projectId", fixture.projectId()), null);
        assertTrue(list.isArray(), list.toString());
        assertEquals(1, list.size(), "The fixture owns its project and its sole purchase order.");
        JsonNode order = list.get(0);
        assertEquals(fixture.projectId(), order.path("projectId").asText());
        assertEquals(fixture.path(), "/purchase-orders/" + order.path("purchaseOrderId").asText());
        return order;
    }

    private static JsonNode returned(JsonNode order, Fixture fixture) {
        assertEquals(1, order.path("returns").size());
        JsonNode back = order.path("returns").get(0);
        assertEquals(fixture.returnId(), back.path("returnId").asText());
        return back;
    }

    private static void assertSupplierSummaryInputs(Fixture fixture, JsonNode order) {
        assertEquals(fixture.supplierName(), order.path("supplierName").asText());
        assertEquals(fixture.replacement() ? "closed" : "received", order.path("status").asText());
        assertEquals(1, order.path("lines").size());
        JsonNode line = order.path("lines").get(0);
        assertEquals(fixture.lineId(), line.path("lineId").asText());
        assertDecimal("2.5000", line.path("unitPrice"));
        assertDecimal("4.0000", line.path("returnedQuantity"));
        assertDecimal(fixture.replacement() ? "4.0000" : "0.0000", line.path("replacementQuantity"));
        assertDecimal(fixture.replacement() ? "4.0000" : "0.0000", line.path("remainingQuantity"));
        JsonNode back = returned(order, fixture);
        assertEquals(fixture.lineId(), back.path("lineId").asText());
        assertEquals(fixture.replacement(), back.path("replacement").asBoolean());
        assertDecimal("4.0000", back.path("quantity"));
        // Four returned units at 2.5 are again the inputs for the supplier's 10 due, including a closed replacement.
    }

    private static void assertWithdrawn(JsonNode back) {
        assertNullFields(back, "creditAmount", "creditAmountText", "creditNote", "creditedBy", "creditedAt");
        JsonNode changes = back.path("creditChanges");
        assertTrue(changes.isArray(), changes.toString());
        assertEquals(2, changes.size(), "Reloading and retrying the original credit must preserve both changes exactly once.");
        assertDecimal("8.0001", changes.get(0).path("amount"));
        assertEquals("8.0001", changes.get(0).path("amountText").asText());
        assertEquals("CN-CHANGED", changes.get(0).path("creditNote").asText());
        JsonNode withdrawal = changes.get(1);
        assertNullFields(withdrawal, "amount", "amountText", "creditNote");
        assertEquals("Supplier cancelled the credit", withdrawal.path("reason").asText());
        assertEquals(DEMO_OWNER, withdrawal.path("changedBy").asText());
        assertFalse(withdrawal.path("changedAt").asText().isBlank());
    }

    private static void assertNullFields(JsonNode row, String... fields) {
        for (String field : fields) {
            assertTrue(row.has(field), "Missing field " + field + ": " + row);
            assertTrue(row.get(field).isNull(), "Expected null " + field + ": " + row);
        }
    }

    private static void assertDecimal(String expected, JsonNode actual) {
        assertTrue(actual.isNumber(), String.valueOf(actual));
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()), actual.toString());
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        }
        var response = mvc.perform(request).andReturn().getResponse();
        JsonNode envelope = mapper.readTree(response.getContentAsString());
        assertEquals(200, response.getStatus(), envelope.toString());
        assertTrue(envelope.path("success").asBoolean(), envelope.toString());
        return envelope.path("data");
    }

    private record Fixture(String projectId, String path, String lineId, String returnId, String supplierName, boolean replacement) {
        String creditPath() {
            return path + "/returns/" + returnId + "/credit";
        }
    }
}
