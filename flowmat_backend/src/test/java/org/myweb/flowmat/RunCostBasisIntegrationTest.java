package org.myweb.flowmat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.catalog.domain.entity.Item;
import org.myweb.flowmat.domain.catalog.repository.ItemRepository;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class RunCostBasisIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ItemRepository items;

    @Test
    void finishedRunUsesItsEndTimeAndIgnoresLaterPriceChanges() throws Exception {
        String input = item("9");
        history(input, "2029-01-01T00:00:00Z", "1", "2");
        history(input, "2031-01-01T00:00:00Z", "2", "9");
        String run = run(); record(run, input, "input", "3000", "g");
        call(post("/production-runs/" + run + "/finish"), Map.of("actualOutputQty", 2)).andExpect(status().isOk());
        jdbc.update("update production_run set actual_end_at = '2030-01-01T00:00:00Z' where production_run_id = ?", run);
        call(get("/production-runs/" + run + "/cost"), null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.materialCost").value(6.0))
            .andExpect(jsonPath("$.data.costPerUnit").value(3.0))
            .andExpect(jsonPath("$.data.costBasis").value("HISTORICAL"))
            .andExpect(jsonPath("$.data.costBasisAt").value("2030-01-01T00:00:00Z"));
        jdbc.update("update item set unit_cost = 12 where item_id = ?", input);
        history(input, "2032-01-01T00:00:00Z", "9", "12");
        call(get("/production-runs/" + run + "/cost"), null)
            .andExpect(jsonPath("$.data.materialCost").value(6.0));
    }

    @Test
    void ongoingCostsAreCurrentAndLegacyFinishedDatesAreExplicitlyEstimated() throws Exception {
        String input = item("4"); String run = run(); record(run, input, "input", "3", "kg");
        call(get("/production-runs/" + run + "/cost"), null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.materialCost").value(12.0))
            .andExpect(jsonPath("$.data.costBasis").value("CURRENT"));
        call(post("/production-runs/" + run + "/finish"), Map.of("actualOutputQty", 2)).andExpect(status().isOk());
        // Until the separate finish command stores actual_end_at, the API must not claim an exact historical price.
        jdbc.update("update production_run set actual_end_at = null where production_run_id = ?", run);
        call(get("/production-runs/" + run + "/cost"), null)
            .andExpect(jsonPath("$.data.costBasis").value("ESTIMATED"))
            .andExpect(jsonPath("$.data.lines[0].costBasis").value("ESTIMATED"));
    }

    @Test
    void unknownHistoricalPriceIsNotReplacedByCurrentPrice() throws Exception {
        String input = item("8");
        history(input, "2029-01-01T00:00:00Z", "1", null);
        history(input, "2031-01-01T00:00:00Z", null, "8");
        String run = run(); record(run, input, "input", "3", "kg");
        call(post("/production-runs/" + run + "/finish"), Map.of("actualOutputQty", 2)).andExpect(status().isOk());
        jdbc.update("update production_run set actual_end_at = '2030-01-01T00:00:00Z' where production_run_id = ?", run);
        call(get("/production-runs/" + run + "/cost"), null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.costComplete").value(false))
            .andExpect(jsonPath("$.data.materialCost").value(0.0));

    }

    @Test
    void missingHistoryAtKnownEndTimeMarksTheLineAndTotalEstimated() throws Exception {
        String input = item("4"); String run = run(); record(run, input, "input", "3", "kg");
        call(post("/production-runs/" + run + "/finish"), Map.of("actualOutputQty", 2)).andExpect(status().isOk());
        jdbc.update("update production_run set actual_end_at = '2030-01-01T00:00:00Z' where production_run_id = ?", run);
        call(get("/production-runs/" + run + "/cost"), null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.materialCost").value(12.0))
            .andExpect(jsonPath("$.data.costBasis").value("ESTIMATED"))
            .andExpect(jsonPath("$.data.estimated").value(true))
            .andExpect(jsonPath("$.data.lines[0].costBasis").value("ESTIMATED"));
    }

    @Test
    void incompatibleLegacyUnitsCannotProduceAnApparentlyCompleteCost() throws Exception {
        String input = item("4"); String run = run(); record(run, input, "input", "3", "kg");
        jdbc.update("update production_run_item set unit = 'h' where production_run_id = ?", run);
        call(get("/production-runs/" + run + "/cost"), null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.materialCost").value(0.0))
            .andExpect(jsonPath("$.data.costComplete").value(false))
            .andExpect(jsonPath("$.data.lines[0].quantity").doesNotExist());
    }

    @Test
    void invalidLegacyCrossProjectReferenceDoesNotRevealItemMetadataOrCountItsPrice() throws Exception {
        String input = item("4"); String run = run(); record(run, input, "input", "3", "kg");
        String project = "foreign-cost-" + UUID.randomUUID().toString().substring(0,8);
        jdbc.update("insert into project(project_id,project_name,owner_id) values(?, 'Foreign cost', ?)", project, DEMO_OWNER);
        jdbc.update("update item set project_id = ?, item_code = 'FOREIGN-CODE', item_name = 'Private item' where item_id = ?", project, input);
        call(get("/production-runs/" + run + "/cost"), null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.materialCost").value(0.0))
            .andExpect(jsonPath("$.data.costComplete").value(false))
            .andExpect(jsonPath("$.data.lines[0].itemCode").value(input))
            .andExpect(jsonPath("$.data.lines[0].itemName").doesNotExist());
    }

    private String item(String price) {
        Item item = new Item(); String id = "cost-basis-" + UUID.randomUUID().toString().substring(0,8);
        item.setItemId(id); item.setProjectId(DEMO_PROJECT); item.setItemCode(id); item.setItemName(id);
        item.setItemType("material"); item.setResourceCategory("material"); item.setUnitId("unit_kg");
        item.setItemStatus("active"); item.setLotManageYn("N"); item.setDeletedYn("N");
        item.setUnitCost(price == null ? null : new BigDecimal(price)); items.save(item); return id;
    }
    private void history(String item, String at, String before, String after) {
        jdbc.update("insert into item_cost_history(item_cost_history_id,project_id,item_id,previous_unit_cost,unit_cost,changed_by,changed_at) "
            + "values(?,?,?, ?,?,?,cast(? as timestamptz))", UUID.randomUUID().toString(), DEMO_PROJECT, item,
            before == null ? null : new BigDecimal(before), after == null ? null : new BigDecimal(after), DEMO_OWNER, at);
    }
    private String run() throws Exception {
        return mapper.readTree(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "runType", "simulation", "plannedOutputQty", 2)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray()).path("data").path("productionRunId").asText();
    }
    private void record(String run, String item, String direction, String qty, String unit) throws Exception {
        call(post("/production-runs/" + run + "/items"), Map.of("itemId", item, "direction", direction,
            "plannedQty", new BigDecimal(qty), "actualQty", new BigDecimal(qty), "unit", unit)).andExpect(status().isOk());
    }
    private ResultActions call(MockHttpServletRequestBuilder request, Map<String,?> body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        return mvc.perform(request);
    }
}
