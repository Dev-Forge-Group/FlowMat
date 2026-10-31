package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** PO6: optional fields normalized to no value must identify the same receipt on retry. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Timeout(60)
class PurchaseReceiptBlankReplayRegressionIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @ParameterizedTest(name = "identical {0} with {1} replays without writing")
    @MethodSource("unicodeBlanks")
    void anIdenticalReceiptWithUnicodeBlankOptionalTextReplays(String field, String label, String blank) throws Exception {
        assertTrue(blank.isBlank(), "The fixture is blank under the existing receipt text rule.");
        assertFalse(blank.trim().isEmpty(), "The fixture exposes the difference from ASCII trimming.");
        Fixture fixture = fixture();
        UUID requestId = UUID.randomUUID();
        Map<String, Object> body = receipt(fixture, requestId, field, blank);
        ok(call(post(fixture.receiptPath()), body));
        Snapshot before = snapshot(fixture);
        assertTrue(before.order().path("receipts").get(0).path(field).isNull());

        Reply repeated = call(post(fixture.receiptPath()), body);
        Snapshot after = snapshot(fixture);

        assertAll("An identical receipt with blank optional text is a safe replay",
            () -> assertEquals(200, repeated.status(), repeated.message()),
            () -> assertEquals(before.order(), repeated.data()),
            () -> assertEquals(before, after, "Replay must not change the order, stock, LOTs or ledger."));
    }

    static Stream<Arguments> unicodeBlanks() {
        return Stream.of("location", "lotNo").flatMap(field -> Stream.of(
            Arguments.of(field, "EM SPACE", "\u2003"),
            Arguments.of(field, "IDEOGRAPHIC SPACE", "\u3000"),
            Arguments.of(field, "mixed Unicode and ASCII whitespace", " \t\u2003\u3000\r\n")));
    }

    @ParameterizedTest(name = "null and blank {0} are the same receipt")
    @ValueSource(strings = {"location", "lotNo"})
    void nullEmptyAndBlankOptionalFieldsShareTheSavedIdentity(String field) throws Exception {
        Fixture fixture = fixture();
        UUID requestId = UUID.randomUUID();
        ok(call(post(fixture.receiptPath()), receipt(fixture, requestId, field, null)));
        Snapshot before = snapshot(fixture);

        for (String blank : List.of("", " \t\r\n", "\u2003", "\u3000")) {
            Reply repeated = call(post(fixture.receiptPath()), receipt(fixture, requestId, field, blank));
            Snapshot after = snapshot(fixture);
            assertAll("Equivalent absent optional fields replay without writes",
                () -> assertEquals(200, repeated.status(), repeated.message()),
                () -> assertEquals(before.order(), repeated.data()),
                () -> assertEquals(before, after));
        }
    }

    @Test
    void aNonblankUnicodeLocationStaysDistinctFromNoLocation() throws Exception {
        String location = "\u00a0";
        assertFalse(location.isBlank(), "A nonbreaking space is preserved by the existing text rule.");
        Fixture fixture = fixture();
        UUID requestId = UUID.randomUUID();
        Map<String, Object> body = receipt(fixture, requestId, "location", location);
        ok(call(post(fixture.receiptPath()), body));
        Snapshot before = snapshot(fixture);
        assertEquals(location, before.order().path("receipts").get(0).path("location").asText());

        assertEquals(before.order(), ok(call(post(fixture.receiptPath()), body)));
        Reply changed = call(post(fixture.receiptPath()), receipt(fixture, requestId, "location", null));

        assertAll("A real location cannot be changed by reusing a receipt key",
            () -> assertEquals(409, changed.status(), changed.message()),
            () -> assertEquals("requestId already belongs to a different receipt.", changed.message()),
            () -> assertEquals(before, snapshot(fixture)));
    }

    private Fixture fixture() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 12);
        String project = ok(call(post("/projects"), Map.of("projectName", "Blank replay " + tag,
            "ownerId", DEMO_OWNER))).path("projectId").asText();
        assertEquals(0, jdbc.queryForObject("select count(*) from storage_location where project_id=?", Integer.class, project));
        String item = ok(call(post("/items"), Map.of("projectId", project, "itemCode", "BLANK-" + tag,
            "itemName", "Blank replay material", "itemType", "material", "unitId", "unit_kg", "lotManageYn", "N")))
            .path("itemId").asText();
        JsonNode draft = ok(call(post("/purchase-orders"), Map.of("projectId", project, "supplierName", "Blank supplier",
            "lines", List.of(Map.of("itemId", item, "quantity", 10)))));
        String order = draft.path("purchaseOrderId").asText();
        JsonNode ordered = ok(call(post("/purchase-orders/" + order + "/order"), null));
        assertEquals("ordered", ordered.path("status").asText());
        assertFalse(ordered.path("lines").get(0).path("lotTracked").asBoolean());
        return new Fixture(project, item, order, ordered.path("lines").get(0).path("lineId").asText());
    }

    private Snapshot snapshot(Fixture fixture) throws Exception {
        JsonNode order = ok(call(get(fixture.path()), null));
        assertEquals(1, order.path("receipts").size());
        assertEquals("ordered", order.path("status").asText());
        assertEquals(0, new BigDecimal("2.5").compareTo(order.path("lines").get(0).path("receivedQuantity").decimalValue()));
        List<Map<String, Object>> receipts = jdbc.queryForList(
            "select * from purchase_receipt where purchase_order_id=? order by receipt_id", fixture.order());
        List<Map<String, Object>> stock = jdbc.queryForList(
            "select * from inventory where project_id=? order by inventory_id", fixture.project());
        List<Map<String, Object>> lots = jdbc.queryForList(
            "select * from lot_master where project_id=? order by lot_id", fixture.project());
        assertEquals(1, receipts.size());
        assertEquals(1, stock.size());
        assertTrue(lots.isEmpty());
        assertEquals(0, new BigDecimal("2.5").compareTo((BigDecimal) stock.get(0).get("quantity")));
        return new Snapshot(order,
            jdbc.queryForMap("select * from purchase_order where purchase_order_id=?", fixture.order()),
            jdbc.queryForList("select * from purchase_order_line where purchase_order_id=? order by line_id", fixture.order()),
            receipts, stock, lots,
            jdbc.queryForList("select * from inventory_transaction where project_id=? order by inventory_transaction_id", fixture.project()));
    }

    private static Map<String, Object> receipt(Fixture fixture, UUID requestId, String field, String value) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("lineId", fixture.line());
        line.put("quantity", new BigDecimal("2.5000"));
        line.put(field, value);
        return Map.of("requestId", requestId, "lines", List.of(line));
    }

    private Reply call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        }
        var response = mvc.perform(request).andReturn().getResponse();
        return new Reply(response.getStatus(), mapper.readTree(response.getContentAsString()));
    }

    private static JsonNode ok(Reply reply) {
        assertEquals(200, reply.status(), reply.message());
        return reply.data();
    }

    private record Fixture(String project, String item, String order, String line) {
        String path() { return "/purchase-orders/" + order; }
        String receiptPath() { return path() + "/receipts"; }
    }

    private record Reply(int status, JsonNode body) {
        JsonNode data() { return body.path("data"); }
        String message() { return body.path("message").asText(); }
    }

    private record Snapshot(JsonNode order, Map<String, Object> orderRow, List<Map<String, Object>> lines,
                            List<Map<String, Object>> receipts, List<Map<String, Object>> stock,
                            List<Map<String, Object>> lots, List<Map<String, Object>> ledger) {
    }
}
