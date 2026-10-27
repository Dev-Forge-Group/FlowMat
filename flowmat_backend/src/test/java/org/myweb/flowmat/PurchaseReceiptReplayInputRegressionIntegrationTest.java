package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Date;
import java.util.List;
import java.util.Locale;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** PO5-PO6 input validation applies to fresh receipts and retries without rechecking mutable LOT business state. */
@AutoConfigureMockMvc
@Timeout(60)
class PurchaseReceiptReplayInputRegressionIntegrationTest extends IntegrationTestSupport {
    private static final String LOCATION = "WH-A";
    private static final String EXPIRY = "2042-01-01";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @ParameterizedTest(name = "{0} rejects {1} before receipt replay comparison")
    @MethodSource("invalidText")
    void invalidStoredTextIsRefusedOnFreshAndReplayedReceipts(String field, String label, String prefix, String suffix) throws Exception {
        Fixture fixture = fixture();
        State committed = state(fixture);
        String canonical = "location".equals(field) ? LOCATION : fixture.lotNo();
        String token = paddedToken(canonical, prefix, suffix);
        String location = "location".equals(field) ? token : quote(LOCATION);
        String lotNo = "lotNo".equals(field) ? token : quote(fixture.lotNo());
        String expectedMessage = field + " contains a character PostgreSQL cannot store.";

        assertRefused(fixture, committed, UUID.randomUUID(), "2", location, lotNo, quote(EXPIRY), expectedMessage);
        assertRefused(fixture, committed, fixture.requestId(), "2", location, lotNo, quote(EXPIRY), expectedMessage);
    }

    static Stream<Arguments> invalidText() {
        return Stream.of("location", "lotNo").flatMap(field -> Stream.of(
            Arguments.of(field, "leading NUL", "\\u0000", ""),
            Arguments.of(field, "trailing NUL", "", "\\u0000"),
            Arguments.of(field, "unpaired high surrogate", "", "\\ud800"),
            Arguments.of(field, "unpaired low surrogate", "\\udc00", "")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-date", "2042-13-01"})
    void anInvalidExpiryDateIsRefusedOnFreshAndReplayedReceipts(String expiry) throws Exception {
        Fixture fixture = fixture();
        State committed = state(fixture);
        String expectedMessage = "expiryDate is a date like 2041-09-10.";

        assertRefused(fixture, committed, UUID.randomUUID(), "2", quote(LOCATION), quote(fixture.lotNo()), quote(expiry), expectedMessage);
        assertRefused(fixture, committed, fixture.requestId(), "2", quote(LOCATION), quote(fixture.lotNo()), quote(expiry), expectedMessage);
    }

    @Test
    void anExtremeQuantityIsRejectedBeforeTheReplayKeyNormalizesIt() throws Exception {
        Fixture fixture = fixture();
        State committed = state(fixture);
        // This small coefficient overflows scale immediately when its trailing zero is stripped; do not substitute other extreme exponents.
        for (UUID key : List.of(UUID.randomUUID(), fixture.requestId())) {
            Reply rejected = call(post(fixture.receiptPath()), body(fixture, key, "100e2147483647",
                quote(LOCATION), quote(fixture.lotNo()), quote(EXPIRY)));
            State after = state(fixture);
            assertAll("Oversized quantity on a fresh key or a replay",
                () -> assertEquals(400, rejected.status(), rejected.message()),
                () -> assertTrue(rejected.message().toLowerCase(Locale.ROOT).contains("quantity"), rejected.message()),
                () -> assertTrue(rejected.message().toLowerCase(Locale.ROOT).contains("too large"), rejected.message()),
                () -> assertEquals(committed, after, "An invalid quantity must not change the order, stock, ledger or LOT."));
        }
    }

    @ParameterizedTest(name = "{0} remains accepted when replay fields are normalized")
    @MethodSource("acceptedWhitespace")
    void normalWhitespaceOnTheOriginalReceiptStillReplaysWithoutWriting(String label, String prefix, String suffix) throws Exception {
        Fixture fixture = fixture();
        State committed = state(fixture);

        JsonNode replayed = ok(call(post(fixture.receiptPath()), body(fixture, fixture.requestId(), "2.0000",
            paddedToken(LOCATION, prefix, suffix), paddedToken(fixture.lotNo(), prefix, suffix), paddedToken(EXPIRY, prefix, suffix))));

        assertEquals(committed.order().path("receipts").get(0).path("receiptId"), replayed.path("receipts").get(0).path("receiptId"));
        assertEquals(committed, state(fixture), "Whitespace normalization must not create another receipt or change any stored state.");
    }

    static Stream<Arguments> acceptedWhitespace() {
        return Stream.of(Arguments.of("spaces", "  ", "  "), Arguments.of("tabs", "\\t", "\\t"),
            Arguments.of("CRLF", "\\r\\n", "\\r\\n"));
    }

    @Test
    void changingTheExistingLotExpiryDoesNotRevalidateBusinessStateOnTheOriginalReceiptReplay() throws Exception {
        Fixture fixture = fixture();
        // PO6 retries the saved receipt; stock-import S5 still checks current LOT expiry for a new receipt.
        assertEquals(1, jdbc.update("update lot_master set expiry_date=? where lot_id=?", Date.valueOf("2043-01-01"), fixture.lotId()));
        State changedLot = state(fixture);
        assertEquals("2043-01-01", changedLot.lot().path("expiryDate").asText());
        Reply fresh = call(post(fixture.receiptPath()), originalBody(fixture, UUID.randomUUID()));
        assertEquals(400, fresh.status(), fresh.message());
        assertTrue(fresh.message().toLowerCase(Locale.ROOT).contains("expiry"), fresh.message());
        assertEquals(changedLot, state(fixture));

        JsonNode replayed = ok(call(post(fixture.receiptPath()), originalBody(fixture, fixture.requestId())));

        assertEquals(changedLot.order().path("receipts").get(0).path("receiptId"), replayed.path("receipts").get(0).path("receiptId"));
        assertEquals(changedLot, state(fixture), "A saved receipt replay must neither reject the current LOT nor restore its old expiry.");
    }

    private void assertRefused(Fixture fixture, State committed, UUID key, String quantity, String location, String lotNo,
                               String expiry, String expectedMessage) throws Exception {
        Reply rejected = call(post(fixture.receiptPath()), body(fixture, key, quantity, location, lotNo, expiry));
        State after = state(fixture);
        assertAll("Invalid receipt input",
            () -> assertEquals(400, rejected.status(), rejected.message()),
            () -> assertEquals(expectedMessage, rejected.message()),
            () -> assertEquals(committed, after, "Invalid input must not change the complete order, stock, ledger or LOT state."));
    }

    private Fixture fixture() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 12);
        String project = ok(call(post("/projects"), mapper.writeValueAsString(
            Map.of("projectName", "Receipt replay input " + tag, "ownerId", DEMO_OWNER)))).path("projectId").asText();
        String item = ok(call(post("/items"), mapper.writeValueAsString(Map.of("projectId", project, "itemCode", "RRI-" + tag,
            "itemName", "Receipt replay LOT", "itemType", "material", "unitId", "unit_kg", "lotManageYn", "Y"))))
            .path("itemId").asText();
        String order = ok(call(post("/purchase-orders"), mapper.writeValueAsString(Map.of("projectId", project,
            "supplierName", "Receipt input supplier " + tag, "lines", List.of(Map.of("itemId", item, "quantity", 10))))))
            .path("purchaseOrderId").asText();
        String path = "/purchase-orders/" + order;
        String line = ok(call(post(path + "/order"), null)).path("lines").get(0).path("lineId").asText();
        UUID requestId = UUID.randomUUID();
        String lotNo = "RRI-LOT-" + tag;
        JsonNode received = ok(call(post(path + "/receipts"), mapper.writeValueAsString(Map.of("requestId", requestId,
            "lines", List.of(Map.of("lineId", line, "quantity", 2, "location", LOCATION, "lotNo", lotNo, "expiryDate", EXPIRY))))));
        assertEquals(1, received.path("receipts").size());
        JsonNode receipt = received.path("receipts").get(0);
        String stock = receipt.path("inventoryId").asText();
        JsonNode inventory = ok(call(get("/inventories/" + stock), null));
        assertDecimal("2", inventory.path("quantity"));
        String lot = inventory.path("lotId").asText();
        assertTrue(!lot.isBlank() && !"null".equals(lot));
        Fixture fixture = new Fixture(project, order, line, stock, lot, lotNo, requestId);
        State saved = state(fixture);
        assertEquals("ordered", saved.order().path("status").asText());
        assertEquals(2, saved.ledger().size());
        int receiptMovements = 0;
        int creationMovements = 0;
        for (JsonNode movement : saved.ledger()) {
            assertEquals("receipt", movement.path("transactionType").asText());
            if ("purchase_receipt".equals(movement.path("referenceType").asText())) {
                receiptMovements++;
                assertEquals(receipt.path("receiptId"), movement.path("referenceId"));
                assertDecimal("2", movement.path("quantityDelta"));
            } else {
                creationMovements++;
                assertEquals("inventory", movement.path("referenceType").asText());
                assertEquals(stock, movement.path("referenceId").asText());
                assertDecimal("0", movement.path("quantityDelta"));
            }
        }
        assertEquals(1, receiptMovements);
        assertEquals(1, creationMovements);
        assertEquals(EXPIRY, saved.lot().path("expiryDate").asText());
        return fixture;
    }

    private String originalBody(Fixture fixture, UUID key) throws Exception {
        return body(fixture, key, "2", quote(LOCATION), quote(fixture.lotNo()), quote(EXPIRY));
    }

    private String body(Fixture fixture, UUID key, String quantity, String location, String lotNo, String expiry) throws Exception {
        // Keep supplied JSON escapes and numeric tokens literal, including unpaired UTF-16 surrogates.
        return "{\"requestId\":" + quote(key.toString()) + ",\"lines\":[{\"lineId\":" + quote(fixture.lineId())
            + ",\"quantity\":" + quantity + ",\"location\":" + location + ",\"lotNo\":" + lotNo + ",\"expiryDate\":" + expiry + "}]}";
    }

    private static String paddedToken(String value, String prefix, String suffix) {
        return "\"" + prefix + value + suffix + "\"";
    }

    private String quote(String value) throws Exception {
        return mapper.writeValueAsString(value);
    }

    private State state(Fixture fixture) throws Exception {
        return new State(ok(call(get(fixture.path()), null)), ok(call(get("/inventories/" + fixture.stockId()), null)),
            ok(call(get("/inventory-transactions").param("projectId", fixture.projectId()).param("inventoryId", fixture.stockId()), null)),
            ok(call(get("/lots/" + fixture.lotId()), null)));
    }

    private Reply call(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        var response = mvc.perform(request).andReturn().getResponse();
        JsonNode envelope = mapper.readTree(response.getContentAsString());
        return new Reply(response.getStatus(), envelope.path("data"), envelope.path("message").asText());
    }

    private static JsonNode ok(Reply reply) {
        assertEquals(200, reply.status(), reply.message());
        return reply.data();
    }

    private static void assertDecimal(String expected, JsonNode actual) {
        assertTrue(actual.isNumber(), String.valueOf(actual));
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()));
    }

    private record Fixture(String projectId, String orderId, String lineId, String stockId, String lotId, String lotNo, UUID requestId) {
        String path() { return "/purchase-orders/" + orderId; }
        String receiptPath() { return path() + "/receipts"; }
    }

    private record Reply(int status, JsonNode data, String message) {
    }

    private record State(JsonNode order, JsonNode stock, JsonNode ledger, JsonNode lot) {
    }
}
