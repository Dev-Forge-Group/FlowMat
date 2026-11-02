package org.myweb.flowmat;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
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
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** What a BOM revision's finished runs lost against its planned scrap (ADR-034) against real Postgres; fixtures stay in the disposable DB. */
@AutoConfigureMockMvc
class BomScrapPerformanceIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtProvider jwt;
    @Autowired JdbcTemplate jdbc;

    @Test
    void finishedRunsShowWhatEachMaterialLostAgainstItsPlannedScrap() throws Exception {
        String flour = item("unit_kg");
        String sugar = item("unit_kg");
        String rye = item("unit_kg");
        String cake = item("unit_ea");
        // 10 cakes: 2 kg of flour with 5% scrap, 1 kg of sugar; rye may stand in for the flour, 2 kg with 25% scrap.
        String bomId = data(call(post("/boms"), Map.of("projectId", DEMO_PROJECT, "targetItemId", cake, "bomName", "Scrap cake",
            "baseQuantity", 10, "baseUnit", "ea"))).path("bomId").asText();
        String flourLine = data(call(post("/boms/" + bomId + "/lines"), Map.of("childItemId", flour, "quantity", 2, "unit", "kg",
            "scrapRate", 0.05))).path("lines").get(0).path("bomLineId").asText();
        call(post("/boms/" + bomId + "/lines"), Map.of("childItemId", sugar, "quantity", 1, "unit", "kg")).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/lines"), Map.of("childItemId", rye, "quantity", 2, "unit", "kg", "scrapRate", 0.25,
            "substituteFor", flourLine)).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/submit"), null).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/approve"), null).andExpect(status().isOk());
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String flourStock = stock(flour, "200", tag);
        String sugarStock = stock(sugar, "20", tag);
        String ryeStock = stock(rye, "10", tag);

        // Nothing finished yet.
        call(get("/boms/" + bomId + "/scrap-performance"), null)
            .andExpect(jsonPath("$.data.runs").value(0))
            .andExpect(jsonPath("$.data.lines.length()").value(0));

        // Run 1 makes all 20: 4 kg of flour net, 4.2 kg planned, 4.4 kg used.
        String first = run(order(cake, bomId, "First " + tag), 20);
        record(first, flourStock, flour, "4.4");
        record(first, sugarStock, sugar, "2");
        finish(first, 20);
        // Run 2 makes 10 of 20: 2 kg net, 2.1 kg planned; 2.5 kg of rye goes in for the flour, as 2.1 kg of it.
        String second = run(order(cake, bomId, "Second " + tag), 20);
        record(second, ryeStock, rye, "2.5");
        record(second, sugarStock, sugar, "1");
        finish(second, 10);
        // A run still going does not count.
        String open = run(order(cake, bomId, "Open " + tag), 20);
        record(open, flourStock, flour, "100");

        // Flour: 6.5 kg used on 6 kg net is 8.33% lost against 5% planned (decisions 1-4).
        call(get("/boms/" + bomId + "/scrap-performance"), null)
            .andExpect(jsonPath("$.data.runs").value(2))
            .andExpect(jsonPath("$.data.lines[*].itemId").value(not(hasItem(rye))))
            .andExpect(jsonPath("$.data.lines[0].itemId").value(flour))
            .andExpect(jsonPath("$.data.lines[0].plannedScrapRate").value(0.05))
            .andExpect(jsonPath("$.data.lines[0].runs").value(2))
            .andExpect(jsonPath("$.data.lines[0].net").value(6.0))
            .andExpect(jsonPath("$.data.lines[0].planned").value(6.3))
            .andExpect(jsonPath("$.data.lines[0].actual").value(6.5))
            .andExpect(jsonPath("$.data.lines[0].actualLossRate").value(0.0833))
            .andExpect(jsonPath("$.data.lines[0].complete").value(true))
            .andExpect(jsonPath("$.data.lines[1].itemId").value(sugar))
            .andExpect(jsonPath("$.data.lines[1].net").value(3.0))
            .andExpect(jsonPath("$.data.lines[1].actual").value(3.0));
    }

    private String order(String product, String bomId, String title) throws Exception {
        String orderId = data(call(post("/work-orders"), Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "workOrderTitle", title, "targetItemId", product, "targetQuantity", 20, "bomId", bomId))).path("workOrderId").asText();
        call(post("/work-orders/" + orderId + "/approve"), null).andExpect(status().isOk());
        return orderId;
    }

    private String run(String orderId, int planned) throws Exception {
        return data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "workOrderId", orderId, "plannedOutputQty", planned))).path("productionRunId").asText();
    }

    private void record(String runId, String inventoryId, String itemId, String quantity) throws Exception {
        call(post("/production-runs/" + runId + "/items"), Map.of("inventoryId", inventoryId, "itemId", itemId, "direction", "input",
            "plannedQty", new BigDecimal(quantity), "actualQty", new BigDecimal(quantity), "unit", "kg")).andExpect(status().isOk());
    }

    private void finish(String runId, int output) throws Exception {
        call(post("/production-runs/" + runId + "/finish"), Map.of("actualOutputQty", output)).andExpect(status().isOk());
    }

    private String item(String unit) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into item(item_id,project_id,item_code,item_name,unit_id,item_status,unit_cost) values(?,?,?,?,?,'active',0)",
            id, DEMO_PROJECT, "SP-" + id.substring(0, 8), "Scrap performance test", unit);
        return id;
    }

    private String stock(String itemId, String quantity, String tag) throws Exception {
        return data(call(post("/inventories"), Map.of("projectId", DEMO_PROJECT, "itemId", itemId, "quantity", new BigDecimal(quantity),
            "location", "SP-" + tag))).path("inventoryId").asText();
    }

    private JsonNode data(ResultActions result) throws Exception {
        return mapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        return mvc.perform(request);
    }
}
