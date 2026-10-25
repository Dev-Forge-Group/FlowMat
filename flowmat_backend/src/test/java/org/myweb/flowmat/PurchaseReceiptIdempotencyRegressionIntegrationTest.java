package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class PurchaseReceiptIdempotencyRegressionIntegrationTest extends IntegrationTestSupport {
    private static final String EXPIRY = "2042-12-31";
    private static final String CONFLICT = "requestId already belongs to a different receipt.";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @ParameterizedTest
    @CsvSource({"WH|A,LOT-B,WH,A|LOT-B", "WH,A|LOT-B,WH|A,LOT-B"})
    void aSeparatorInLocationCannotBeMovedIntoTheLotNumberUnderTheSameRequestId(
        String firstLocation, String firstLot, String otherLocation, String otherLot) throws Exception {
        Fixture fixture = fixture();
        UUID request = UUID.randomUUID();
        Snapshot before = received(fixture, request, firstLocation, firstLot);

        Reply refused = call(post(fixture.path() + "/receipts"),
            receipt(fixture, request, "2.5", otherLocation, otherLot));

        assertEquals(before, snapshot(fixture), "A changed receipt must not add or alter stock, LOTs or ledger rows.");
        assertStatus(409, refused);
        assertEquals(CONFLICT, refused.message());
    }

    @Test
    void anIdenticalReceiptAllowsQuantityScaleAndFieldWhitespaceWithoutChangingStoredDatesOrStock() throws Exception {
        Fixture fixture = fixture();
        UUID request = UUID.randomUUID();
        Snapshot before = received(fixture, request, "WH|A", "LOT-B");

        Reply repeated = call(post(fixture.path() + "/receipts"),
            receipt(fixture, request, "2.5", "  WH|A  ", "  LOT-B  "));

        assertStatus(200, repeated);
        assertEquals(before.order(), repeated.data());
        assertEquals(before, snapshot(fixture), "Replaying a receipt must leave all counts and persisted LOT dates unchanged.");
    }

    @Test
    void anOrdinaryDifferentLocationUnderTheSameRequestIdIsStillAConflict() throws Exception {
        Fixture fixture = fixture();
        UUID request = UUID.randomUUID();
        Snapshot before = received(fixture, request, "WH-A", "LOT-C");

        Reply refused = call(post(fixture.path() + "/receipts"),
            receipt(fixture, request, "2.5000", "WH-B", "LOT-C"));

        assertEquals(before, snapshot(fixture), "A refused location change must leave the original receipt untouched.");
        assertStatus(409, refused);
        assertEquals(CONFLICT, refused.message());
    }

    private Fixture fixture() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 12);
        String project = data(call(post("/projects"), Map.of(
            "projectName", "Receipt identity " + tag, "ownerId", DEMO_OWNER))).path("projectId").asText();
        assertEquals(0, jdbc.queryForObject("select count(*) from storage_location where project_id=?",
            Integer.class, project), "Free-form locations need a project without a registered location list.");
        String item = data(call(post("/items"), Map.of("projectId", project, "itemCode", "RECEIPT-" + tag,
            "itemName", "Receipt identity material", "itemType", "material", "unitId", "unit_kg", "lotManageYn", "Y")))
            .path("itemId").asText();
        JsonNode draft = data(call(post("/purchase-orders"), Map.of("projectId", project, "supplierName", "Receipt supplier",
            "lines", List.of(Map.of("itemId", item, "quantity", 10)))));
        String order = draft.path("purchaseOrderId").asText();
        JsonNode ordered = data(call(post("/purchase-orders/" + order + "/order"), null));
        assertEquals("ordered", ordered.path("status").asText());
        assertTrue(ordered.path("lines").get(0).path("lotTracked").asBoolean());
        return new Fixture(project, item, order, ordered.path("lines").get(0).path("lineId").asText());
    }

    private Snapshot received(Fixture fixture, UUID request, String location, String lotNo) throws Exception {
        data(call(post(fixture.path() + "/receipts"), receipt(fixture, request, "2.5000", location, lotNo)));
        // Read back after commit so timestamp offsets have the same persisted form on every comparison.
        Snapshot saved = snapshot(fixture);
        JsonNode receipt = saved.order().path("receipts").get(0);
        assertEquals(location, receipt.path("location").asText());
        assertEquals(lotNo, receipt.path("lotNo").asText());
        assertEquals(location, saved.stock().path("location").asText());
        assertEquals(lotNo, saved.lot().path("lotNo").asText());
        assertEquals(EXPIRY, saved.lot().path("expiryDate").asText());
        assertEquals(request, saved.receipts().get(0).get("request_id"));
        return saved;
    }

    private Snapshot snapshot(Fixture fixture) throws Exception {
        JsonNode order = data(call(get(fixture.path()), null));
        assertEquals("ordered", order.path("status").asText());
        assertEquals(1, order.path("receipts").size());
        assertAmount("2.5", order.path("lines").get(0).path("receivedQuantity"));
        assertAmount("7.5", order.path("lines").get(0).path("remainingQuantity"));
        String receiptId = order.path("receipts").get(0).path("receiptId").asText();
        String inventoryId = order.path("receipts").get(0).path("inventoryId").asText();
        JsonNode stock = data(call(get("/inventories/" + inventoryId), null));
        assertAmount("2.5", stock.path("quantity"));
        assertAmount("0", stock.path("reservedQuantity"));
        assertAmount("2.5", stock.path("availableQuantity"));
        String lotId = stock.path("lotId").asText();
        assertFalse(lotId.isBlank());
        JsonNode lot = data(call(get("/lots/" + lotId), null));
        assertEquals(fixture.item(), lot.path("itemId").asText());
        assertAmount("2.5", lot.path("quantityOnHand"));

        List<Map<String, Object>> receipts = jdbc.queryForList("select receipt_id,line_id,request_id,quantity,inventory_id,"
            + "location,lot_no,received_by,received_at from purchase_receipt where purchase_order_id=? order by receipt_id",
            fixture.order());
        List<Map<String, Object>> inventories = jdbc.queryForList("select inventory_id,item_id,lot_id,location,quantity,"
            + "reserved_quantity,available_quantity,version,created_at,updated_at from inventory where project_id=? order by inventory_id",
            fixture.project());
        List<Map<String, Object>> lots = jdbc.queryForList("select lot_id,lot_no,received_at,produced_at,expiry_date,created_at,updated_at "
            + "from lot_master where project_id=? order by lot_id", fixture.project());
        // Creating the empty stock record also writes a zero-quantity 'inventory' ledger row.
        List<Map<String, Object>> movements = jdbc.queryForList("select inventory_transaction_id,inventory_id,lot_id,quantity_delta,"
            + "quantity_after,reference_id,created_at from inventory_transaction where project_id=? "
            + "and reference_type='purchase_receipt' order by inventory_transaction_id", fixture.project());
        assertEquals(1, receipts.size());
        assertEquals(1, inventories.size());
        assertEquals(1, lots.size());
        assertEquals(1, movements.size());
        assertEquals(receiptId, movements.get(0).get("reference_id"));
        assertEquals(0, new BigDecimal("2.5").compareTo((BigDecimal) receipts.get(0).get("quantity")));
        assertEquals(0, new BigDecimal("2.5").compareTo((BigDecimal) inventories.get(0).get("quantity")));
        assertEquals(0, new BigDecimal("2.5").compareTo((BigDecimal) movements.get(0).get("quantity_delta")));
        return new Snapshot(order, stock, lot,
            jdbc.queryForMap("select status,updated_at,ended_at from purchase_order where purchase_order_id=?", fixture.order()),
            jdbc.queryForMap("select quantity,received_quantity from purchase_order_line where line_id=?", fixture.line()),
            receipts, inventories, lots, movements,
            jdbc.queryForObject("select count(*) from inventory_transaction where project_id=?", Integer.class, fixture.project()));
    }

    private static Map<String, Object> receipt(Fixture fixture, UUID request, String quantity, String location, String lotNo) {
        return Map.of("requestId", request, "lines", List.of(Map.of("lineId", fixture.line(),
            "quantity", new BigDecimal(quantity), "location", location, "lotNo", lotNo, "expiryDate", EXPIRY)));
    }

    private Reply call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        var response = mvc.perform(request).andReturn().getResponse();
        return new Reply(response.getStatus(), mapper.readTree(response.getContentAsString()));
    }

    private static JsonNode data(Reply reply) {
        assertStatus(200, reply);
        return reply.data();
    }

    private static void assertStatus(int expected, Reply reply) {
        assertEquals(expected, reply.status(), reply.body().toString());
    }

    private static void assertAmount(String expected, JsonNode actual) {
        assertTrue(actual.isNumber(), "The quantity must be a JSON number.");
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()));
    }

    private record Fixture(String project, String item, String order, String line) {
        String path() { return "/purchase-orders/" + order; }
    }

    private record Reply(int status, JsonNode body) {
        JsonNode data() { return body.path("data"); }
        String message() { return body.path("message").asText(); }
    }

    private record Snapshot(JsonNode order, JsonNode stock, JsonNode lot, Map<String, Object> orderRow,
        Map<String, Object> lineRow, List<Map<String, Object>> receipts, List<Map<String, Object>> inventories,
        List<Map<String, Object>> lots, List<Map<String, Object>> movements, int allMovementCount) {
    }
}
