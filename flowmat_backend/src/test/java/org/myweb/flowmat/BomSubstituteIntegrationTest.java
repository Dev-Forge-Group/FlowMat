package org.myweb.flowmat;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

/** Substitute materials on BOM lines (ADR-032) against real Postgres; fixtures stay in the disposable DB. */
@AutoConfigureMockMvc
class BomSubstituteIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtProvider jwt;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aSubstituteJoinsItsMainLinesGroupAndOnlyTheMainLineIsPlanned() throws Exception {
        String flour = item("unit_kg", "10");
        String rye = item("unit_kg", "12");
        String spelt = item("unit_kg", "11");
        String sugar = item("unit_kg", "2");
        String cake = item("unit_ea", null);
        // 10 cakes take 2 kg of flour and 1 kg of sugar. Rye may stand in for the flour: 2 kg with 25% scrap, so 2.5 kg.
        String bomId = draft(cake);
        String flourLine = data(call(post("/boms/" + bomId + "/lines"), Map.of("childItemId", flour, "quantity", 2, "unit", "kg")))
            .path("lines").get(0).path("bomLineId").asText();
        String sugarLine = data(call(post("/boms/" + bomId + "/lines"), Map.of("childItemId", sugar, "quantity", 1, "unit", "kg")))
            .path("lines").get(1).path("bomLineId").asText();
        String ryeLine = data(call(post("/boms/" + bomId + "/lines"), Map.of("childItemId", rye, "quantity", 2, "unit", "kg",
            "scrapRate", 0.25, "substituteFor", flourLine))).path("lines").get(2).path("bomLineId").asText();
        call(get("/boms/" + bomId), null)
            .andExpect(jsonPath("$.data.lines[0].substituteGroup").value(flourLine))
            .andExpect(jsonPath("$.data.lines[0].priority").value(0))
            .andExpect(jsonPath("$.data.lines[0].substituteFor").doesNotExist())
            .andExpect(jsonPath("$.data.lines[2].substituteGroup").value(flourLine))
            .andExpect(jsonPath("$.data.lines[2].priority").value(1))
            .andExpect(jsonPath("$.data.lines[2].substituteFor").value(flourLine));

        // Naming a substitute puts the new one at the end of the same group (decision 3).
        String speltLine = data(call(post("/boms/" + bomId + "/lines"), Map.of("childItemId", spelt, "quantity", 2, "unit", "kg",
            "substituteFor", ryeLine))).path("lines").get(3).path("bomLineId").asText();
        call(get("/boms/" + bomId), null)
            .andExpect(jsonPath("$.data.lines[3].priority").value(2))
            .andExpect(jsonPath("$.data.lines[3].substituteFor").value(flourLine));
        call(post("/boms/" + bomId + "/lines"), Map.of("childItemId", item("unit_kg", null), "quantity", 1, "unit", "kg",
            "lineType", "waste", "substituteFor", sugarLine))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Only a material line that is not a phantom can be in a substitute group."));
        call(post("/boms/" + bomId + "/lines"), Map.of("childItemId", item("unit_kg", null), "quantity", 1, "unit", "kg",
            "substituteFor", sugarLine, "substituteGroup", "G"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Give substituteFor or substituteGroup, not both."));
        call(post("/boms/" + bomId + "/lines"), Map.of("childItemId", item("unit_kg", null), "quantity", 1, "unit", "kg",
            "substituteFor", UUID.randomUUID().toString()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("substituteFor must be a line of this BOM."));
        call(delete("/boms/" + bomId + "/lines/" + speltLine), null).andExpect(status().isOk());

        call(post("/boms/" + bomId + "/submit"), null).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/approve"), null).andExpect(status().isOk());

        // 20 cakes: 4 kg of flour and 2 kg of sugar are planned and costed; 5 kg of rye could replace the flour (decision 5).
        call(get("/boms/" + bomId + "/requirements").param("quantity", "20"), null)
            .andExpect(jsonPath("$.data.lines.length()").value(2))
            .andExpect(jsonPath("$.data.lines[0].childItemId").value(flour))
            .andExpect(jsonPath("$.data.lines[0].requiredItemQuantity").value(4.0))
            .andExpect(jsonPath("$.data.lines[0].substitutes.length()").value(1))
            .andExpect(jsonPath("$.data.lines[0].substitutes[0].childItemId").value(rye))
            .andExpect(jsonPath("$.data.lines[0].substitutes[0].requiredItemQuantity").value(5.0))
            .andExpect(jsonPath("$.data.lines[0].substitutes[0].lineCost").value(60.0))
            .andExpect(jsonPath("$.data.lines[0].substitutes[0].priority").value(1))
            .andExpect(jsonPath("$.data.lines[0].substitutes[0].mainPerUnit").value(0.8))
            .andExpect(jsonPath("$.data.lines[1].substitutes.length()").value(0))
            .andExpect(jsonPath("$.data.materialCost").value(44.0));
        call(get("/boms/cost-rollup").param("projectId", DEMO_PROJECT), null)
            .andExpect(jsonPath("$.data.items[?(@.itemId == '" + cake + "')].rolledUpCost").value(hasItem(2.2)));
        // No rye in stock does not stop anything: 4 kg of flour makes 20 cakes.
        stock(flour, "4", "B1");
        stock(sugar, "10", "B1");
        call(get("/boms/" + bomId + "/buildable"), null)
            .andExpect(jsonPath("$.data.buildable").value(20))
            .andExpect(jsonPath("$.data.lines[*].itemId").value(not(hasItem(rye))));

        // A new revision keeps the group and its order.
        JsonNode revision = data(call(post("/boms/" + bomId + "/revisions"), null));
        String newFlourLine = revision.path("lines").get(0).path("bomLineId").asText();
        assertEquals(newFlourLine, revision.path("lines").get(2).path("substituteFor").asText());
        assertEquals(1, revision.path("lines").get(2).path("priority").asInt());
        // Removing the main line leaves one material, which is no group any more.
        JsonNode left = data(call(delete("/boms/" + revision.path("bomId").asText() + "/lines/" + newFlourLine), null)).path("lines");
        assertTrue(left.get(1).path("substituteGroup").isNull());
        assertEquals(0, left.get(1).path("priority").asInt());
    }

    @Test
    void aSubstituteInStockOrInUseCountsAsItsMainMaterial() throws Exception {
        String flour = item("unit_kg", "10");
        String rye = item("unit_kg", "12");
        String sugar = item("unit_kg", "2");
        String cake = item("unit_ea", null);
        String bomId = draft(cake);
        String flourLine = data(call(post("/boms/" + bomId + "/lines"), Map.of("childItemId", flour, "quantity", 2, "unit", "kg")))
            .path("lines").get(0).path("bomLineId").asText();
        call(post("/boms/" + bomId + "/lines"), Map.of("childItemId", sugar, "quantity", 1, "unit", "kg")).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/lines"), Map.of("childItemId", rye, "quantity", 2, "unit", "kg", "scrapRate", 0.25,
            "substituteFor", flourLine)).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/submit"), null).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/approve"), null).andExpect(status().isOk());
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String flourStock = stock(flour, "2", tag);
        String ryeStock = stock(rye, "10", tag);
        stock(sugar, "10", tag);

        String orderId = data(call(post("/work-orders"), Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "workOrderTitle", "Substitute order " + tag, "targetItemId", cake, "targetQuantity", 20, "bomId", bomId)))
            .path("workOrderId").asText();
        call(post("/work-orders/" + orderId + "/approve"), null).andExpect(status().isOk());
        // 4 kg of flour needed, 2 in stock; 10 kg of rye stands for 8 kg of flour, so the shortage is covered (decision 7).
        call(get("/work-orders/" + orderId + "/readiness"), null)
            .andExpect(jsonPath("$.data.materials[0].itemId").value(flour))
            .andExpect(jsonPath("$.data.materials[0].shortageQuantity").value(2.0))
            .andExpect(jsonPath("$.data.checks[?(@.code == 'materials')].status").value(hasItem("warn")))
            .andExpect(jsonPath("$.data.checks[?(@.code == 'materials')].message").value(hasItem(allOf(
                containsString("Approved substitutes cover"), containsString("SB-" + rye.substring(0, 8) + " 10 kg")))));

        // 2.5 kg of rye reserved by hand stands for 2 kg of flour, so the BOM reserves only the other 2 (decision 6).
        call(post("/work-orders/" + orderId + "/allocations"), Map.of("lines", List.of(Map.of("itemId", rye, "quantity", 2.5))))
            .andExpect(status().isOk());
        call(post("/work-orders/" + orderId + "/allocations"), null)
            .andExpect(jsonPath("$.data.plan[?(@.itemId == '" + flour + "')].needed").value(hasItem(4.0)))
            .andExpect(jsonPath("$.data.plan[?(@.itemId == '" + flour + "')].allocatedBefore").value(hasItem(2.0)))
            .andExpect(jsonPath("$.data.plan[?(@.itemId == '" + flour + "')].allocatedNow").value(hasItem(2.0)))
            .andExpect(jsonPath("$.data.plan[?(@.itemId == '" + flour + "')].shortage").value(hasItem(0)));

        // Half the flour goes in as rye: 2 kg of flour and 2.5 kg of rye cover the 4 kg.
        String run = data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "workOrderId", orderId, "plannedOutputQty", 20))).path("productionRunId").asText();
        call(post("/production-runs/" + run + "/items"), recording(flourStock, flour, "2")).andExpect(status().isOk());
        call(post("/production-runs/" + run + "/items"), recording(ryeStock, rye, "2.5")).andExpect(status().isOk());
        call(get("/work-orders/" + orderId + "/readiness"), null)
            .andExpect(jsonPath("$.data.materials[?(@.itemId == '" + flour + "')].requiredQuantity").value(hasItem(0.0)));
        call(get("/material-requirements").param("projectId", DEMO_PROJECT), null)
            .andExpect(jsonPath("$.data.lines[?(@.itemId == '" + flour + "')].orders[?(@.workOrderId == '" + orderId + "')].required")
                .value(hasItem(0.0)));

        // The run used what the BOM allows: the flour's standard is shared out by use, and rye is no stray material (decision 8).
        call(get("/production-runs/" + run + "/material-usage"), null)
            .andExpect(jsonPath("$.data.lines[?(@.itemId == '" + flour + "')].standard").value(hasItem(2.0)))
            .andExpect(jsonPath("$.data.lines[?(@.itemId == '" + flour + "')].variance").value(hasItem(0.0)))
            .andExpect(jsonPath("$.data.lines[?(@.itemId == '" + rye + "')].inBom").value(hasItem(true)))
            .andExpect(jsonPath("$.data.lines[?(@.itemId == '" + rye + "')].substituteFor").value(hasItem(flour)))
            .andExpect(jsonPath("$.data.lines[?(@.itemId == '" + rye + "')].standard").value(hasItem(2.5)))
            .andExpect(jsonPath("$.data.lines[?(@.itemId == '" + rye + "')].variance").value(hasItem(0.0)));
    }

    @Test
    void approvalRefusesAGroupOfOneTiedPrioritiesAndAnythingButPlainMaterials() throws Exception {
        String bomId = draft(item("unit_ea", null));
        line(bomId, item("unit_kg", null), "material", "ALONE", 0);
        line(bomId, item("unit_kg", null), "material", "TIED", 1);
        line(bomId, item("unit_kg", null), "material", "TIED", 1);
        line(bomId, item("unit_kg", null), "by_product", "MIXED", 0);
        line(bomId, item("unit_kg", null), "material", "MIXED", 1);
        call(post("/boms/" + bomId + "/submit"), null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(allOf(
                containsString("is alone in its substitute group"),
                containsString("has the same priority as another material of the group"),
                containsString("only a material line can be in a substitute group"),
                not(containsString("not supported yet")))));
    }

    private void line(String bomId, String itemId, String type, String group, int priority) {
        jdbc.update("insert into bom_line(bom_line_id,bom_id,child_item_id,quantity,unit,line_type,substitute_group,priority) values(?,?,?,1,'kg',?,?,?)",
            UUID.randomUUID().toString(), bomId, itemId, type, group, priority);
    }

    private String item(String unit, String unitCost) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into item(item_id,project_id,item_code,item_name,unit_id,item_status,unit_cost) values(?,?,?,?,?,'active',?)",
            id, DEMO_PROJECT, "SB-" + id.substring(0, 8), "Substitute test", unit, unitCost == null ? BigDecimal.ZERO : new BigDecimal(unitCost));
        return id;
    }

    private String stock(String itemId, String quantity, String tag) throws Exception {
        return data(call(post("/inventories"), Map.of("projectId", DEMO_PROJECT, "itemId", itemId, "quantity", new BigDecimal(quantity),
            "location", "SB-" + tag))).path("inventoryId").asText();
    }

    private static Map<String, Object> recording(String inventoryId, String itemId, String quantity) {
        return Map.of("inventoryId", inventoryId, "itemId", itemId, "direction", "input", "plannedQty", new BigDecimal(quantity),
            "actualQty", new BigDecimal(quantity), "unit", "kg");
    }

    private String draft(String product) throws Exception {
        return data(call(post("/boms"), Map.of("projectId", DEMO_PROJECT, "targetItemId", product, "bomName", "Substitute cake",
            "baseQuantity", 10, "baseUnit", "ea"))).path("bomId").asText();
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
