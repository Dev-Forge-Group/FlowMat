package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** PO42: invalid NCR or reason text remains invalid when an otherwise identical purchase return is retried. */
@AutoConfigureMockMvc
class PurchaseReturnNcrReplayInputRegressionIntegrationTest extends IntegrationTestSupport {
    private static final String INVALID_NCR_TEXT = "nonconformityId contains a character PostgreSQL cannot store.";
    private static final String INVALID_REASON_TEXT = "reason contains a character PostgreSQL cannot store.";
    private static final String RETURN_REASON = "Return the NCR LOT";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;

    @ParameterizedTest(name = "rejects {0} on a fresh request and an identical-key retry")
    @MethodSource("invalidNcrText")
    void invalidNcrTextIsRejectedBeforeReplayNormalization(String label, String prefix, String suffix) throws Exception {
        Fixture fixture = fixture();
        UUID requestId = UUID.randomUUID();
        String invalidId = ncrToken(fixture, prefix, suffix);
        State untouched = state(fixture);
        assertEquals(0, untouched.order().path("returns").size());
        assertDecimal("10", untouched.stock().path("quantity"));

        Reply fresh = call(post(fixture.returnPath()), returnJson(fixture, requestId, invalidId));
        State afterFresh = state(fixture);
        assertAll(label + " on a fresh request",
            () -> assertEquals(400, fresh.status(), fresh.message()),
            () -> assertEquals(INVALID_NCR_TEXT, fresh.message()),
            () -> assertEquals(untouched, afterFresh, "Invalid input must not write a return, stock, ledger or NCR."));

        // Reusing the failed key with the actual NCR must still create the first return normally.
        JsonNode saved = data(call(post(fixture.returnPath()), returnJson(fixture, requestId, ncrToken(fixture, "", ""))));
        State committed = state(fixture);
        assertOneLinkedReturn(fixture, saved, committed);

        Reply replay = call(post(fixture.returnPath()), returnJson(fixture, requestId, invalidId));
        State afterReplay = state(fixture);
        assertAll(label + " on an identical-key retry",
            () -> assertEquals(400, replay.status(), replay.message()),
            () -> assertEquals(INVALID_NCR_TEXT, replay.message()),
            () -> assertEquals(committed, afterReplay, "A rejected replay must preserve the committed return and stock."));
    }

    static Stream<Arguments> invalidNcrText() {
        // These are JSON escape fragments, so malformed UTF-16 reaches the API without Java/UTF-8 replacement.
        return Stream.of(
            Arguments.of("leading NUL", "\\u0000", ""),
            Arguments.of("trailing NUL", "", "\\u0000"),
            Arguments.of("NUL surrounded by accepted whitespace", " \\t", "\\u0000\\r\\n "),
            Arguments.of("unpaired high surrogate", "", "\\ud800"),
            Arguments.of("unpaired low surrogate", "\\udc00", "")
        );
    }

    @ParameterizedTest(name = "rejects {0} in the reason on a fresh request and an identical-key retry")
    @MethodSource("invalidReasonText")
    void invalidReasonTextIsRejectedBeforeReplayNormalization(String label, String prefix, String suffix) throws Exception {
        Fixture fixture = fixture();
        UUID requestId = UUID.randomUUID();
        String canonicalNcr = ncrToken(fixture, "", "");
        String invalidReason = "\"" + prefix + RETURN_REASON + suffix + "\"";
        State untouched = state(fixture);
        assertEquals(0, untouched.order().path("returns").size());
        assertDecimal("10", untouched.stock().path("quantity"));

        Reply fresh = call(post(fixture.returnPath()), returnJson(fixture, requestId, canonicalNcr, invalidReason));
        State afterFresh = state(fixture);
        assertAll(label + " reason on a fresh request",
            () -> assertEquals(400, fresh.status(), fresh.message()),
            () -> assertEquals(INVALID_REASON_TEXT, fresh.message()),
            () -> assertEquals(untouched, afterFresh, "Invalid reason text must not write a return, stock, ledger or NCR."));

        JsonNode saved = data(call(post(fixture.returnPath()), returnJson(fixture, requestId, canonicalNcr)));
        State committed = state(fixture);
        assertOneLinkedReturn(fixture, saved, committed);
        assertEquals(RETURN_REASON, committed.order().path("returns").get(0).path("reason").asText());

        Reply replay = call(post(fixture.returnPath()), returnJson(fixture, requestId, canonicalNcr, invalidReason));
        State afterReplay = state(fixture);
        assertAll(label + " reason on an identical-key retry",
            () -> assertEquals(400, replay.status(), replay.message()),
            () -> assertEquals(INVALID_REASON_TEXT, replay.message()),
            () -> assertEquals(committed, afterReplay, "A rejected reason replay must preserve the committed return and stock."));
    }

    static Stream<Arguments> invalidReasonText() {
        return Stream.of(
            Arguments.of("leading NUL", "\\u0000", ""),
            Arguments.of("trailing NUL", "", "\\u0000")
        );
    }

    @ParameterizedTest(name = "keeps allowed {0} normalization on fresh and repeated requests")
    @MethodSource("allowedNcrWhitespace")
    void ordinaryWhitespaceStillNamesTheSameNcr(String label, String prefix, String suffix) throws Exception {
        Fixture fixture = fixture();
        UUID requestId = UUID.randomUUID();
        String paddedId = ncrToken(fixture, prefix, suffix);
        JsonNode saved = data(call(post(fixture.returnPath()), returnJson(fixture, requestId, paddedId)));
        State committed = state(fixture);
        assertOneLinkedReturn(fixture, saved, committed);

        data(call(post(fixture.returnPath()), returnJson(fixture, requestId, ncrToken(fixture, "", ""))));
        assertEquals(committed, state(fixture), label + " must compare equal to the normalized saved NCR.");
        data(call(post(fixture.returnPath()), returnJson(fixture, requestId, paddedId)));
        assertEquals(committed, state(fixture), label + " must not cause a duplicate return or stock decrease.");
    }

    static Stream<Arguments> allowedNcrWhitespace() {
        return Stream.of(
            Arguments.of("plain id", "", ""),
            Arguments.of("surrounding spaces", "  ", "  "),
            Arguments.of("surrounding tabs", "\\t", "\\t"),
            Arguments.of("surrounding CR/LF", "\\r\\n", "\\r\\n")
        );
    }

    @Test
    void aValidReplayStillSucceedsAfterTheNcrDispositionChanges() throws Exception {
        Fixture fixture = fixture();
        UUID requestId = UUID.randomUUID();
        String canonicalNcr = ncrToken(fixture, "", "");
        String originalBody = returnJson(fixture, requestId, canonicalNcr);
        JsonNode saved = data(call(post(fixture.returnPath()), originalBody));
        State committed = state(fixture);
        assertOneLinkedReturn(fixture, saved, committed);

        ok(put("/nonconformities/" + fixture.ncrId()), Map.of("disposition", "use_as_is"));
        State afterDispositionChange = state(fixture);
        assertEquals("open", afterDispositionChange.ncr().path("status").asText());
        assertEquals("use_as_is", afterDispositionChange.ncr().path("disposition").asText());
        assertEquals(committed.order(), afterDispositionChange.order());
        assertEquals(committed.stock(), afterDispositionChange.stock());
        assertEquals(committed.ledger(), afterDispositionChange.ledger());

        // A new return still checks the NCR's current disposition (PO42).
        Reply fresh = call(post(fixture.returnPath()), returnJson(fixture, UUID.randomUUID(), canonicalNcr));
        assertEquals(409, fresh.status(), fresh.message());
        assertEquals(fixture.ncrNo() + " is not set to return to supplier.", fresh.message());
        assertEquals(afterDispositionChange, state(fixture));

        // The same key and contents answer as the order is now (PO27), rather than repeating the stock movement.
        JsonNode replayed = data(call(post(fixture.returnPath()), originalBody));
        assertEquals(1, replayed.path("returns").size());
        assertEquals(saved.path("returns").get(0).path("returnId"), replayed.path("returns").get(0).path("returnId"));
        assertEquals(fixture.ncrId(), replayed.path("returns").get(0).path("nonconformityId").asText());
        assertEquals(afterDispositionChange, state(fixture));
    }

    private Fixture fixture() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        String project = ok(post("/projects"), Map.of("projectName", "NCR replay text " + tag, "ownerId", DEMO_OWNER))
            .path("projectId").asText();
        String item = ok(post("/items"), Map.of("projectId", project, "itemCode", "NRI-" + tag, "itemName", "NCR return lot",
            "itemType", "material", "unitId", "unit_kg", "lotManageYn", "Y")).path("itemId").asText();
        JsonNode draft = ok(post("/purchase-orders"), Map.of("projectId", project, "supplierName", "Replay supplier " + tag,
            "lines", List.of(Map.of("itemId", item, "quantity", 10))));
        String orderPath = "/purchase-orders/" + draft.path("purchaseOrderId").asText();
        String line = ok(post(orderPath + "/order"), null).path("lines").get(0).path("lineId").asText();
        String stock = ok(post(orderPath + "/receipts"), Map.of("requestId", UUID.randomUUID().toString(),
            "lines", List.of(Map.of("lineId", line, "quantity", 10, "location", "WH-A", "lotNo", "NRI-LOT-" + tag,
                "expiryDate", "2042-01-01"))))
            .path("receipts").get(0).path("inventoryId").asText();
        String lot = ok(get("/inventories/" + stock), null).path("lotId").asText();
        assertTrue(!lot.isBlank() && !"null".equals(lot), "The receipt must create the item's real LOT.");

        JsonNode raised = ok(post("/nonconformities"), Map.of("projectId", project, "title", "Return the received LOT " + tag,
            "itemId", item, "lotId", lot, "severity", "major"));
        String ncr = raised.path("nonconformityId").asText();
        JsonNode ready = ok(put("/nonconformities/" + ncr), Map.of("disposition", "return_to_supplier"));
        assertEquals("open", ready.path("status").asText());
        assertEquals("return_to_supplier", ready.path("disposition").asText());
        assertEquals(item, ready.path("itemId").asText());
        assertEquals(lot, ready.path("lotId").asText());
        return new Fixture(project, orderPath, line, stock, ncr, ready.path("ncrNo").asText());
    }

    private String ncrToken(Fixture fixture, String prefix, String suffix) throws Exception {
        String encoded = mapper.writeValueAsString(fixture.ncrId());
        return "\"" + prefix + encoded.substring(1, encoded.length() - 1) + suffix + "\"";
    }

    private String returnJson(Fixture fixture, UUID requestId, String ncrToken) throws Exception {
        return returnJson(fixture, requestId, ncrToken, mapper.writeValueAsString(RETURN_REASON));
    }

    private String returnJson(Fixture fixture, UUID requestId, String ncrToken, String reasonToken) throws Exception {
        String body = mapper.writeValueAsString(Map.of("requestId", requestId, "lineId", fixture.lineId(),
            "inventoryId", fixture.stockId(), "quantity", 2, "replacement", false));
        return body.substring(0, body.length() - 1) + ",\"nonconformityId\":" + ncrToken + ",\"reason\":" + reasonToken + "}";
    }

    private State state(Fixture fixture) throws Exception {
        return new State(
            ok(get(fixture.orderPath()), null),
            ok(get("/inventories/" + fixture.stockId()), null),
            ok(get("/inventory-transactions").param("projectId", fixture.projectId()).param("inventoryId", fixture.stockId()), null),
            ok(get("/nonconformities/" + fixture.ncrId()), null)
        );
    }

    private static void assertOneLinkedReturn(Fixture fixture, JsonNode mutation, State committed) {
        JsonNode returns = committed.order().path("returns");
        assertEquals(1, returns.size());
        assertEquals(1, mutation.path("returns").size());
        JsonNode saved = returns.get(0);
        assertEquals(saved.path("returnId"), mutation.path("returns").get(0).path("returnId"));
        assertEquals(fixture.ncrId(), saved.path("nonconformityId").asText());
        assertEquals(fixture.ncrNo(), saved.path("ncrNo").asText());
        assertDecimal("2", saved.path("quantity"));
        assertDecimal("8", committed.stock().path("quantity"));
        assertEquals("open", committed.ncr().path("status").asText());
        assertEquals("return_to_supplier", committed.ncr().path("disposition").asText());
        int returnMovements = 0;
        for (JsonNode entry : committed.ledger()) {
            if ("purchase_return".equals(entry.path("referenceType").asText())) {
                returnMovements++;
                assertEquals(saved.path("returnId").asText(), entry.path("referenceId").asText());
                assertDecimal("-2", entry.path("quantityDelta"));
            }
        }
        assertEquals(1, returnMovements, "Exactly one committed stock adjustment must belong to the return.");
    }

    private static void assertDecimal(String expected, JsonNode actual) {
        assertTrue(actual.isNumber(), String.valueOf(actual));
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()), actual.toString());
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return data(call(request, body == null ? null : mapper.writeValueAsString(body)));
    }

    private Reply call(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        var response = mvc.perform(request).andReturn().getResponse();
        JsonNode envelope = mapper.readTree(response.getContentAsString());
        return new Reply(response.getStatus(), envelope.path("data"), envelope.path("message").asText());
    }

    private static JsonNode data(Reply reply) {
        assertEquals(200, reply.status(), reply.message());
        return reply.data();
    }

    private record Fixture(String projectId, String orderPath, String lineId, String stockId, String ncrId, String ncrNo) {
        String returnPath() {
            return orderPath + "/returns";
        }
    }

    private record State(JsonNode order, JsonNode stock, JsonNode ledger, JsonNode ncr) {
    }

    private record Reply(int status, JsonNode data, String message) {
    }
}
