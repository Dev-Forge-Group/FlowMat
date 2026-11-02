package org.myweb.flowmat;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Optional BOM materials a work order picks (ADR-033) against real Postgres; fixtures stay in the disposable DB. */
@AutoConfigureMockMvc
class BomOptionalIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtProvider jwt;
    @Autowired JdbcTemplate jdbc;

    @Test
    void anOptionalMaterialIsPlannedOnlyForAWorkOrderThatPicksIt() throws Exception {
        String flour = item("unit_kg", "10");
        String box = item("unit_ea", "3");
        String cake = item("unit_ea", null);
        // 10 cakes take 2 kg of flour; a gift box each is optional.
        String bomId = draft(cake);
        call(post("/boms/" + bomId + "/lines"), Map.of("childItemId", flour, "quantity", 2, "unit", "kg")).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/lines"), Map.of("childItemId", box, "quantity", 10, "unit", "ea", "optionalYn", "Y"))
            .andExpect(status().isOk());
        call(post("/boms/" + bomId + "/submit"), null).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/approve"), null).andExpect(status().isOk());

        // Without a work order the box is listed apart, not planned or costed (decision 7).
        call(get("/boms/" + bomId + "/requirements").param("quantity", "20"), null)
            .andExpect(jsonPath("$.data.lines.length()").value(1))
            .andExpect(jsonPath("$.data.lines[0].childItemId").value(flour))
            .andExpect(jsonPath("$.data.optionals.length()").value(1))
            .andExpect(jsonPath("$.data.optionals[0].childItemId").value(box))
            .andExpect(jsonPath("$.data.optionals[0].requiredItemQuantity").value(20.0))
            .andExpect(jsonPath("$.data.optionals[0].lineCost").value(60.0))
            .andExpect(jsonPath("$.data.materialCost").value(40.0));
        call(get("/boms/cost-rollup").param("projectId", DEMO_PROJECT), null)
            .andExpect(jsonPath("$.data.items[?(@.itemId == '" + cake + "')].rolledUpCost").value(hasItem(2.0)));
        String tag = UUID.randomUUID().toString().substring(0, 8);
        stock(flour, "10", tag);
        String boxStock = stock(box, "50", tag);

        // One order picks the box, the other does not (decisions 2, 6).
        String plain = order(cake, bomId, "Plain " + tag, null);
        String gift = order(cake, bomId, "Gift " + tag, List.of(box));
        call(get("/work-orders/" + gift), null).andExpect(jsonPath("$.data.optionalItemIds", contains(box)));
        call(get("/work-orders/" + plain), null).andExpect(jsonPath("$.data.optionalItemIds", empty()));
        call(post("/work-orders/" + plain + "/approve"), null).andExpect(status().isOk());
        call(post("/work-orders/" + gift + "/approve"), null).andExpect(status().isOk());

        call(get("/work-orders/" + plain + "/readiness"), null)
            .andExpect(jsonPath("$.data.materials[*].itemId").value(not(hasItem(box))));
        call(get("/work-orders/" + gift + "/readiness"), null)
            .andExpect(jsonPath("$.data.materials[?(@.itemId == '" + box + "')].requiredQuantity").value(hasItem(20.0)));
        call(get("/material-requirements").param("projectId", DEMO_PROJECT), null)
            .andExpect(jsonPath("$.data.lines[?(@.itemId == '" + box + "')].orders[*].workOrderId").value(contains(gift)));
        call(post("/work-orders/" + gift + "/allocations"), null)
            .andExpect(jsonPath("$.data.plan[?(@.itemId == '" + box + "')].allocatedNow").value(hasItem(20.0)));

        // The gift order's run freezes the box into its plan; the plain order's run does not.
        String giftRun = start(gift);
        call(get("/production-runs/" + giftRun + "/items"), null)
            .andExpect(jsonPath("$.data[?(@.itemId == '" + box + "')].plannedQty").value(hasItem(20.0)));
        String plainRun = start(plain);
        call(get("/production-runs/" + plainRun + "/items"), null)
            .andExpect(jsonPath("$.data[*].itemId").value(not(hasItem(box))));
        // A box put into the plain order's run is beyond its plan, but the BOM knows it as an optional material (decision 8).
        call(post("/production-runs/" + plainRun + "/items"), Map.of("inventoryId", boxStock, "itemId", box, "direction", "input",
            "plannedQty", 5, "actualQty", 5, "unit", "ea")).andExpect(status().isOk());
        call(get("/production-runs/" + plainRun + "/material-usage"), null)
            .andExpect(jsonPath("$.data.lines[?(@.itemId == '" + box + "')].inBom").value(hasItem(false)))
            .andExpect(jsonPath("$.data.lines[?(@.itemId == '" + box + "')].optional").value(hasItem(true)))
            .andExpect(jsonPath("$.data.lines[?(@.itemId == '" + flour + "')].optional").value(hasItem(false)));
    }

    @Test
    void aDraftKeepsItsPicksUnlessGivenNewOnesAndOnlyOptionalMaterialsCanBePicked() throws Exception {
        String flour = item("unit_kg", null);
        String box = item("unit_ea", null);
        String cake = item("unit_ea", null);
        String bomId = draft(cake);
        call(post("/boms/" + bomId + "/lines"), Map.of("childItemId", flour, "quantity", 2, "unit", "kg")).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/lines"), Map.of("childItemId", box, "quantity", 10, "unit", "ea", "optionalYn", "Y"))
            .andExpect(status().isOk());
        call(post("/boms/" + bomId + "/submit"), null).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/approve"), null).andExpect(status().isOk());

        // A material every batch uses cannot be picked (decision 5).
        call(post("/work-orders"), orderBody(cake, bomId, "Wrong pick", List.of(flour)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Not an optional material of the work order's BOM: OP-" + flour.substring(0, 8) + "."));

        String orderId = order(cake, bomId, "Picks", List.of(box, " " + box + " "));
        call(get("/work-orders/" + orderId), null).andExpect(jsonPath("$.data.optionalItemIds", contains(box)));
        // Without optionalItemIds the picks stay; an empty list takes them all out (decision 4).
        call(put("/work-orders/" + orderId), orderBody(cake, bomId, "Picks renamed", null))
            .andExpect(jsonPath("$.data.optionalItemIds", contains(box)));
        call(put("/work-orders/" + orderId), orderBody(cake, bomId, "Picks renamed", List.of()))
            .andExpect(jsonPath("$.data.optionalItemIds", empty()));
        call(put("/work-orders/" + orderId), orderBody(cake, bomId, "Picks renamed", List.of(box)))
            .andExpect(jsonPath("$.data.optionalItemIds", contains(box)));

        // An order without a BOM can name items of the project, but cannot be approved with them (decision 5).
        String noBomProduct = item("unit_ea", null);
        String noBom = order(noBomProduct, null, "No BOM", List.of(box));
        call(post("/work-orders/" + noBom + "/approve"), null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("takes optional materials, so it needs a BOM")));
    }

    @Test
    void anOptionalMaterialCannotBeInASubstituteGroup() throws Exception {
        String bomId = draft(item("unit_ea", null));
        jdbc.update("insert into bom_line(bom_line_id,bom_id,child_item_id,quantity,unit,line_type,substitute_group,priority,optional_yn) values(?,?,?,1,'kg','material','G',0,'N')",
            UUID.randomUUID().toString(), bomId, item("unit_kg", null));
        jdbc.update("insert into bom_line(bom_line_id,bom_id,child_item_id,quantity,unit,line_type,substitute_group,priority,optional_yn) values(?,?,?,1,'kg','material','G',1,'Y')",
            UUID.randomUUID().toString(), bomId, item("unit_kg", null));
        call(post("/boms/" + bomId + "/submit"), null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("an optional material cannot be in a substitute group")));
    }

    private String order(String product, String bomId, String title, List<String> picks) throws Exception {
        return data(call(post("/work-orders"), orderBody(product, bomId, title, picks))).path("workOrderId").asText();
    }

    private Map<String, Object> orderBody(String product, String bomId, String title, List<String> picks) {
        Map<String, Object> body = new HashMap<>(Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "workOrderTitle", title, "targetItemId", product, "targetQuantity", 20));
        if (bomId != null) body.put("bomId", bomId);
        if (picks != null) body.put("optionalItemIds", picks);
        return body;
    }

    private String start(String orderId) throws Exception {
        return data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "workOrderId", orderId, "plannedOutputQty", 20))).path("productionRunId").asText();
    }

    private String item(String unit, String unitCost) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into item(item_id,project_id,item_code,item_name,unit_id,item_status,unit_cost) values(?,?,?,?,?,'active',?)",
            id, DEMO_PROJECT, "OP-" + id.substring(0, 8), "Optional test", unit, unitCost == null ? BigDecimal.ZERO : new BigDecimal(unitCost));
        return id;
    }

    private String stock(String itemId, String quantity, String tag) throws Exception {
        return data(call(post("/inventories"), Map.of("projectId", DEMO_PROJECT, "itemId", itemId, "quantity", new BigDecimal(quantity),
            "location", "OP-" + tag))).path("inventoryId").asText();
    }

    private String draft(String product) throws Exception {
        return data(call(post("/boms"), Map.of("projectId", DEMO_PROJECT, "targetItemId", product, "bomName", "Optional cake",
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
