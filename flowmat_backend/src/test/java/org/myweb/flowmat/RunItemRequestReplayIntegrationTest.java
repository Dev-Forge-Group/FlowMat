package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A run recording sent again after its reply was lost records nothing twice (docs/domain/production-run-recording.md
 * RR1-RR4), against real Postgres. The browser keeps the request key of a recording it did not hear back about and sends
 * it with the same contents; the server answers with what it already recorded.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class RunItemRequestReplayIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void theSameRecordingSentAgainIsRecordedOnceAndADifferentOneUnderItsKeyIsRefused() throws Exception {
        Fixture f = fixture();
        String key = UUID.randomUUID().toString();
        Map<String, Object> input = input(f, f.soonStock(), "2", key);
        String first = ok(post(f.runPath() + "/items"), input).path("productionRunItemId").asText();
        Map<String, Object> afterFirst = state(f);
        assertThat(ok(post(f.runPath() + "/items"), input).path("productionRunItemId").asText()).isEqualTo(first);
        assertThat(state(f)).as("The resent recording moves no stock and adds no row").isEqualTo(afterFirst);
        quantity(f.soonStock(), "4");

        // The same key with other contents is a mistake, not a retry (RR2).
        call(post(f.runPath() + "/items"), input(f, f.soonStock(), "3", key), 409);
        call(post(f.runPath() + "/items"), input(f, f.lateStock(), "2", key), 409);
        assertThat(state(f)).isEqualTo(afterFirst);

        // A new key is a new recording, and no key at all keeps the old behaviour (RR4).
        ok(post(f.runPath() + "/items"), input(f, f.soonStock(), "2", UUID.randomUUID().toString()));
        ok(post(f.runPath() + "/items"), input(f, f.soonStock(), "1", null));
        ok(post(f.runPath() + "/items"), input(f, f.soonStock(), "1", null));
        quantity(f.soonStock(), "0");
        assertThat(jdbc.queryForObject("select count(*) from production_run_item where production_run_id=?", Integer.class,
                f.runId())).isEqualTo(4);

        // A replay of a recording that was cancelled meanwhile answers with it as it is now, and moves nothing.
        ok(post(f.runPath() + "/items/" + first + "/cancel"), Map.of("reason", "Wrong LOT"));
        Map<String, Object> afterCancel = state(f);
        JsonNode replay = ok(post(f.runPath() + "/items"), input);
        assertThat(replay.path("productionRunItemId").asText()).isEqualTo(first);
        assertThat(replay.path("cancelled").asBoolean()).isTrue();
        assertThat(state(f)).isEqualTo(afterCancel);
    }

    @Test
    void aSplitOverLotsSentAgainReturnsTheSamePiecesAndTakesNothingMore() throws Exception {
        Fixture f = fixture();
        String key = UUID.randomUUID().toString();
        Map<String, Object> split = fefo(f, "8000", "g", key);
        JsonNode first = ok(post(f.runPath() + "/inputs/fefo"), split);
        assertThat(first.size()).isEqualTo(2);
        quantity(f.soonStock(), "0");
        quantity(f.lateStock(), "7");
        Map<String, Object> afterFirst = state(f);

        JsonNode again = ok(post(f.runPath() + "/inputs/fefo"), split);
        assertThat(ids(again)).isEqualTo(ids(first));
        assertThat(state(f)).as("The resent split takes nothing more").isEqualTo(afterFirst);
        // 8 kg written in kg is the same request.
        assertThat(ids(ok(post(f.runPath() + "/inputs/fefo"), fefo(f, "8", "kg", key)))).isEqualTo(ids(first));
        call(post(f.runPath() + "/inputs/fefo"), fefo(f, "9", "kg", key), 409);
        // The key of a single recording cannot be reused for a split, nor the other way round.
        call(post(f.runPath() + "/items"), input(f, f.lateStock(), "1", key), 409);
        String single = UUID.randomUUID().toString();
        ok(post(f.runPath() + "/items"), input(f, f.lateStock(), "1", single));
        call(post(f.runPath() + "/inputs/fefo"), fefo(f, "1", "kg", single), 409);
        quantity(f.lateStock(), "6");
    }

    @Test
    void aKeyThatCannotBeStoredIsRefusedBeforeAnythingMoves() throws Exception {
        Fixture f = fixture();
        Map<String, Object> before = state(f);
        call(post(f.runPath() + "/items"), input(f, f.soonStock(), "1", "x".repeat(101)), 400);
        call(post(f.runPath() + "/items"), input(f, f.soonStock(), "1", "  "), 400);
        call(post(f.runPath() + "/inputs/fefo"), fefo(f, "1", "kg", "bad\u0000key"), 400);
        assertThat(state(f)).isEqualTo(before);
    }

    private Fixture fixture() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String flour = ok(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", "RR-" + tag, "itemName", "Replay flour " + tag,
                "itemType", "material", "unitId", "unit_kg", "lotManageYn", "Y")).path("itemId").asText();
        String soon = stock(flour, "RR-SOON-" + tag, "2100-01-01", "6");
        String late = stock(flour, "RR-LATE-" + tag, "2100-06-01", "9");
        String run = ok(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
                "plannedOutputQty", 1)).path("productionRunId").asText();
        return new Fixture(flour, soon, late, run);
    }

    private String stock(String item, String lotNo, String expiry, String quantity) throws Exception {
        String lot = ok(post("/lots"), Map.of("projectId", DEMO_PROJECT, "itemId", item, "lotNo", lotNo, "expiryDate", expiry))
                .path("lotId").asText();
        return ok(post("/inventories"), Map.of("projectId", DEMO_PROJECT, "itemId", item, "lotId", lot, "location", lotNo,
                "quantity", new BigDecimal(quantity))).path("inventoryId").asText();
    }

    private static Map<String, Object> input(Fixture f, String stock, String quantity, String key) {
        Map<String, Object> body = new HashMap<>(Map.of("itemId", f.flour(), "inventoryId", stock, "direction", "input",
                "plannedQty", new BigDecimal(quantity), "actualQty", new BigDecimal(quantity), "unit", "kg"));
        if (key != null) body.put("requestId", key);
        return body;
    }

    private static Map<String, Object> fefo(Fixture f, String quantity, String unit, String key) {
        return Map.of("itemId", f.flour(), "quantity", new BigDecimal(quantity), "unit", unit, "requestId", key);
    }

    private Map<String, Object> state(Fixture f) {
        return Map.of(
                "stock", jdbc.queryForList("select inventory_id,quantity,reserved_quantity from inventory where item_id=? "
                        + "order by inventory_id", f.flour()),
                "ledger", jdbc.queryForList("select inventory_transaction_id,quantity_delta from inventory_transaction where item_id=? "
                        + "order by inventory_transaction_id", f.flour()),
                "items", jdbc.queryForList("select production_run_item_id,inventory_id,actual_qty,cancelled_yn from production_run_item "
                        + "where production_run_id=? order by production_run_item_id", f.runId()));
    }

    private void quantity(String inventory, String expected) {
        assertThat(jdbc.queryForObject("select quantity from inventory where inventory_id=?", BigDecimal.class, inventory))
                .isEqualByComparingTo(expected);
    }

    private static List<String> ids(JsonNode rows) {
        List<String> ids = new java.util.ArrayList<>();
        rows.forEach(row -> ids.add(row.path("productionRunItemId").asText()));
        return ids;
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return call(request, body, 200);
    }

    private JsonNode call(MockHttpServletRequestBuilder request, Object body, int expected) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        var response = mvc.perform(request).andReturn().getResponse();
        JsonNode envelope = mapper.readTree(response.getContentAsByteArray());
        assertThat(response.getStatus()).as(envelope.path("message").asText()).isEqualTo(expected);
        return envelope.path("data");
    }

    private record Fixture(String flour, String soonStock, String lateStock, String runId) {
        String runPath() { return "/production-runs/" + runId; }
    }
}
