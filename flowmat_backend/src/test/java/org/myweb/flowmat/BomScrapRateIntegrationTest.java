package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
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

/** Planned scrap on BOM material lines (ADR-031) against real Postgres; fixtures stay in the disposable DB. */
@AutoConfigureMockMvc
class BomScrapRateIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtProvider jwt;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aScrapRateIsPlannedOnTopOfEveryRequirement() throws Exception {
        String flour = item("unit_kg", "10");
        String bread = item("unit_ea", null);
        // 2 kg of flour for 10 loaves, 5% of it lost on the way.
        String bomId = draft(bread, 10);
        call(post("/boms/" + bomId + "/lines"), Map.of("childItemId", flour, "quantity", 2, "unit", "kg", "scrapRate", 0.05))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lines[0].scrapRate").value(0.05));
        call(post("/boms/" + bomId + "/submit"), null).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/approve"), null).andExpect(status().isOk());

        // 20 loaves: 20 / 10 × 2 kg × 1.05 = 4.2 kg, at 10 a kg (decision 1, 4).
        call(get("/boms/" + bomId + "/requirements").param("quantity", "20"), null)
            .andExpect(jsonPath("$.data.lines[0].requiredItemQuantity").value(4.2))
            .andExpect(jsonPath("$.data.lines[0].scrapRate").value(0.05))
            .andExpect(jsonPath("$.data.lines[0].lineCost").value(42.0))
            .andExpect(jsonPath("$.data.materialCost").value(42.0));
        // One loaf rolls up 0.2 kg × 1.05 × 10.
        call(get("/boms/cost-rollup").param("projectId", DEMO_PROJECT), null)
            .andExpect(jsonPath("$.data.items[?(@.itemId == '" + bread + "')].rolledUpCost").value(hasItem(2.1)));
        // What the stock can make plans the same scrap: 4.2 kg of flour makes 20 loaves, not 21.
        call(post("/inventories"), Map.of("projectId", DEMO_PROJECT, "itemId", flour, "quantity", new BigDecimal("4.2"),
            "location", "SC-BUILDABLE"))
            .andExpect(status().isOk());
        call(get("/boms/" + bomId + "/buildable"), null)
            .andExpect(jsonPath("$.data.buildable").value(20));

        // The order checks, reserves and freezes the same 4.2 kg into its run.
        String orderId = data(call(post("/work-orders"), Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "workOrderTitle", "Scrap order", "targetItemId", bread, "targetQuantity", 20, "bomId", bomId)))
            .path("workOrderId").asText();
        call(post("/work-orders/" + orderId + "/approve"), null).andExpect(status().isOk());
        call(get("/work-orders/" + orderId + "/readiness"), null)
            .andExpect(jsonPath("$.data.materials[0].itemId").value(flour))
            .andExpect(jsonPath("$.data.materials[0].requiredQuantity").value(4.2));
        call(post("/work-orders/" + orderId + "/allocations"), null)
            .andExpect(jsonPath("$.data.plan[0].itemId").value(flour))
            .andExpect(jsonPath("$.data.plan[0].needed").value(4.2));
        String run = data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "workOrderId", orderId, "plannedOutputQty", 20))).path("productionRunId").asText();
        BigDecimal planned = jdbc.queryForObject(
            "select planned_qty from production_run_item where production_run_id = ? and quantity_source = 'bom'", BigDecimal.class, run);
        assertEquals(0, new BigDecimal("4.2").compareTo(planned));
    }

    @Test
    void scrapOnAPhantomLineCompoundsWithTheScrapInsideIt() throws Exception {
        String flour = item("unit_kg", null);
        String dough = item("unit_kg", null);
        String bread = item("unit_ea", null);
        // Dough: 1 kg of flour a kg, 5% of the flour lost.
        String doughBom = approvedBom(dough);
        jdbc.update("insert into bom_line(bom_line_id,bom_id,child_item_id,quantity,unit,line_type,scrap_rate) values(?,?,?,1,'kg','material',0.05)",
            UUID.randomUUID().toString(), doughBom, flour);
        // Bread: 2 kg of dough a loaf through the dough's own BOM, 10% of the dough lost.
        String breadBom = draft(bread, 1);
        call(post("/boms/" + breadBom + "/lines"), Map.of("childItemId", dough, "quantity", 2, "unit", "kg", "phantom", true,
            "scrapRate", 0.1))
            .andExpect(status().isOk());
        call(post("/boms/" + breadBom + "/submit"), null).andExpect(status().isOk());
        call(post("/boms/" + breadBom + "/approve"), null).andExpect(status().isOk());

        // One loaf: 2 × 1.1 = 2.2 kg of dough, so 2.2 × 1.05 = 2.31 kg of flour (decision 3).
        call(get("/boms/" + breadBom + "/requirements").param("quantity", "1"), null)
            .andExpect(jsonPath("$.data.lines.length()").value(1))
            .andExpect(jsonPath("$.data.lines[0].childItemId").value(flour))
            .andExpect(jsonPath("$.data.lines[0].viaItemId").value(dough))
            .andExpect(jsonPath("$.data.lines[0].requiredItemQuantity").value(2.31))
            .andExpect(jsonPath("$.data.lines[0].scrapRate").value(0.05));
    }

    @Test
    void aScrapRateOutsideZeroToOneOrOnWhatABatchGivesOffIsRefused() throws Exception {
        String bomId = draft(item("unit_ea", null), 1);
        String flour = item("unit_kg", null);
        for (Object rate : List.of(-0.01, 1.5)) {
            call(post("/boms/" + bomId + "/lines"), Map.of("childItemId", flour, "quantity", 1, "unit", "kg", "scrapRate", rate))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A scrap rate must be from 0 to 1 (0% to 100%)."));
        }
        call(post("/boms/" + bomId + "/lines"), Map.of("childItemId", flour, "quantity", 1, "unit", "kg",
            "scrapRate", new BigDecimal("0.00005")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("at most 4 decimals")));
        call(post("/boms/" + bomId + "/lines"), Map.of("childItemId", item("unit_kg", null), "quantity", 1, "unit", "kg",
            "lineType", "waste", "scrapRate", 0.05))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Only a material line can have a scrap rate."));
        // 100% is the most: the line plans twice its quantity.
        call(post("/boms/" + bomId + "/lines"), Map.of("childItemId", flour, "quantity", 1, "unit", "kg", "scrapRate", 1))
            .andExpect(status().isOk());

        // A line stored without these checks is still caught when the draft is sent for approval.
        jdbc.update("insert into bom_line(bom_line_id,bom_id,child_item_id,quantity,unit,line_type,scrap_rate) values(?,?,?,1,'kg','by_product',0.05)",
            UUID.randomUUID().toString(), bomId, item("unit_kg", null));
        call(post("/boms/" + bomId + "/submit"), null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("only a material line can have a scrap rate")));
    }

    private String item(String unit, String unitCost) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into item(item_id,project_id,item_code,item_name,unit_id,item_status,unit_cost) values(?,?,?,?,?,'active',?)",
            id, DEMO_PROJECT, "SC-" + id.substring(0, 8), "Scrap test", unit, unitCost == null ? BigDecimal.ZERO : new BigDecimal(unitCost));
        return id;
    }

    private String approvedBom(String product) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into bom_header(bom_id,project_id,target_item_id,bom_name,bom_version,base_quantity,base_unit,bom_status,approval_status,created_by) values(?,?,?,'Scrap dough',1,1,'kg','approved','approved',?)",
            id, DEMO_PROJECT, product, DEMO_OWNER);
        return id;
    }

    private String draft(String product, int base) throws Exception {
        return data(call(post("/boms"), Map.of("projectId", DEMO_PROJECT, "targetItemId", product, "bomName", "Scrap bread",
            "baseQuantity", base, "baseUnit", "ea"))).path("bomId").asText();
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
