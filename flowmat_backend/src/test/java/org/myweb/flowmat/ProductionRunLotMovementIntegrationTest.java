package org.myweb.flowmat;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.myweb.flowmat.domain.inventory.repository.InventoryTransactionRepository;
import org.myweb.flowmat.domain.inventory.repository.LotTraceRepository;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class ProductionRunLotMovementIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private InventoryTransactionRepository movementRepository;
    @Autowired private LotTraceRepository traceRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @ParameterizedTest
    @CsvSource({"input, 0, kg, false", "output, 0, kg, false", "input, 0.01, g, false", "output, 0.01, g, false",
        "input, 0, kg, true", "output, 0, kg, true", "input, 0.01, g, true", "output, 0.01, g, true"})
    void aRecordingWithoutAStockMovementDoesNotCreateLotGenealogy(String direction, BigDecimal quantity, String unit,
        boolean zeroFirst) throws Exception {
        Fixture fixture = fixture();
        boolean input = "input".equals(direction);
        if (!zeroFirst) {
            record(fixture, input ? "output" : "input", new BigDecimal(input ? "1" : "2"), "kg");
        }
        String recordingId = record(fixture, direction, quantity, unit);
        if (zeroFirst) {
            record(fixture, input ? "output" : "input", new BigDecimal(input ? "1" : "2"), "kg");
        }

        assertTrue(movementRepository.findAllByReferenceTypeAndReferenceId("production_run_item", recordingId).isEmpty());
        assertNoPhantomGenealogy(fixture, input);
        call(post("/production-runs/" + fixture.runId() + "/items/" + recordingId + "/cancel"),
            Map.of("reason", "Remove a recording that moved no stock")).andExpect(status().isOk());
        assertNoPhantomGenealogy(fixture, input);
    }

    @Test
    void rebuildingClearsALegacyProducerMarkWithoutAnyOutputMovement() throws Exception {
        Fixture fixture = fixture();
        String input = record(fixture, "input", new BigDecimal("2"), "kg");
        record(fixture, "output", BigDecimal.ZERO, "kg");
        // Represent an old zero-output recording that incorrectly marked a LOT as physically produced.
        jdbcTemplate.update("update lot_master set production_run_id = ?, produced_at = current_timestamp where lot_id = ?",
            fixture.runId(), fixture.product().lotId());
        call(get("/lots/" + fixture.product().lotId())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.productionRunId").value(fixture.runId()));

        call(post("/production-runs/" + fixture.runId() + "/items/" + input + "/cancel"),
            Map.of("reason", "Rebuild the remaining LOTs")).andExpect(status().isOk());

        assertFalse(traceRepository.existsByParentLotIdAndChildLotIdAndProductionRunId(fixture.raw().lotId(),
            fixture.product().lotId(), fixture.runId()));
        call(get("/lots/" + fixture.product().lotId())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.productionRunId").value(nullValue()));
        call(get("/inventories/" + fixture.raw().stockId())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.quantity").value(10.0));
        call(get("/inventories/" + fixture.product().stockId())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.quantity").value(0.0));
    }

    @Test
    void realMovementsStillLinkLotsAndCancellationRemovesTheirTrace() throws Exception {
        Fixture fixture = fixture();
        String input = record(fixture, "input", new BigDecimal("2"), "kg");
        record(fixture, "output", BigDecimal.ONE, "kg");

        assertTrue(traceRepository.existsByParentLotIdAndChildLotIdAndProductionRunId(fixture.raw().lotId(),
            fixture.product().lotId(), fixture.runId()));
        call(post("/production-runs/" + fixture.runId() + "/items/" + input + "/cancel"),
            Map.of("reason", "Wrong input LOT")).andExpect(status().isOk());
        assertFalse(traceRepository.existsByParentLotIdAndChildLotIdAndProductionRunId(fixture.raw().lotId(),
            fixture.product().lotId(), fixture.runId()));
        call(get("/lots/" + fixture.product().lotId()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.productionRunId").value(fixture.runId()));
    }

    private void assertNoPhantomGenealogy(Fixture fixture, boolean realOutput) throws Exception {
        assertFalse(traceRepository.existsByParentLotIdAndChildLotIdAndProductionRunId(fixture.raw().lotId(),
            fixture.product().lotId(), fixture.runId()));
        call(get("/lots/" + fixture.product().lotId())).andExpect(status().isOk())
            .andExpect(realOutput ? jsonPath("$.data.productionRunId").value(fixture.runId())
                : jsonPath("$.data.productionRunId").value(nullValue()));
        call(get("/inventories/" + fixture.raw().stockId()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.quantity").value(realOutput ? 10.0 : 8.0));
        call(get("/inventories/" + fixture.product().stockId()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.quantity").value(realOutput ? 1.0 : 0.0));
    }

    private String record(Fixture fixture, String direction, BigDecimal quantity, String unit) throws Exception {
        LotStock stock = "input".equals(direction) ? fixture.raw() : fixture.product();
        return data(call(post("/production-runs/" + fixture.runId() + "/items"), Map.of("itemId", stock.itemId(),
            "inventoryId", stock.stockId(), "direction", direction, "plannedQty", 1, "actualQty", quantity, "unit", unit)))
            .path("productionRunItemId").asText();
    }

    private Fixture fixture() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        LotStock raw = lotStock("RAW-" + tag, 10);
        LotStock product = lotStock("OUT-" + tag, 0);
        String workflowId = data(call(post("/workflows"), Map.of("projectId", DEMO_PROJECT,
            "workflowName", "Lot movement " + tag))).path("workflowId").asText();
        String runId = data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT,
            "workflowId", workflowId, "plannedOutputQty", 1))).path("productionRunId").asText();
        return new Fixture(runId, raw, product);
    }

    private LotStock lotStock(String tag, int quantity) throws Exception {
        String itemId = data(call(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", "MOVE-" + tag,
            "itemName", tag, "unitId", "unit_kg", "lotManageYn", "Y"))).path("itemId").asText();
        String lotId = data(call(post("/lots"), Map.of("projectId", DEMO_PROJECT,
            "itemId", itemId, "lotNo", "MOVE-" + tag))).path("lotId").asText();
        String stockId = data(call(post("/inventories"), Map.of("projectId", DEMO_PROJECT,
            "itemId", itemId, "lotId", lotId, "location", "MOVE-" + tag, "quantity", quantity))).path("inventoryId").asText();
        return new LotStock(itemId, lotId, stockId);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private record LotStock(String itemId, String lotId, String stockId) {
    }

    private record Fixture(String runId, LotStock raw, LotStock product) {
    }
}
