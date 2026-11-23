package org.myweb.flowmat;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.catalog.domain.entity.Item;
import org.myweb.flowmat.domain.catalog.repository.ItemRepository;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** A run's material cost and usage (docs/domain/material-cost.md "실행 재료비", "사용량 차이") against real Postgres. */
@AutoConfigureMockMvc
class RunCostIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ItemRepository itemRepository;

    @Test
    void inputsArePricedInTheirItemsUnitAndCancelledOnesDoNotCount() throws Exception {
        String flour = item("2");
        String salt = item(null);
        String flourStock = stock(flour, "20");
        String saltStock = stock(salt, "5");
        String runId = id(call(post("/production-runs/start"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowId\":\""
            + DEMO_WORKFLOW + "\",\"plannedOutputQty\":4}").andExpect(status().isOk()), "productionRunId");

        // 3000 g of flour is 3 kg; the 2 kg recording is cancelled and does not count.
        record(runId, flourStock, flour, "3000", "g");
        String wrong = record(runId, flourStock, flour, "2", "kg");
        record(runId, saltStock, salt, "1", "kg");
        call(post("/production-runs/" + runId + "/items/" + wrong + "/cancel"), "{\"reason\":\"double entry\"}")
            .andExpect(status().isOk());
        call(post("/production-runs/" + runId + "/finish"), "{\"actualOutputQty\":4}").andExpect(status().isOk());

        call(get("/production-runs/" + runId + "/cost"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lines[?(@.itemId == '" + flour + "')].quantity").value(hasItem(3.0)))
            .andExpect(jsonPath("$.data.lines[?(@.itemId == '" + flour + "')].cost").value(hasItem(6.0)))
            .andExpect(jsonPath("$.data.lines[?(@.itemId == '" + flour + "')].unit").value(hasItem("kg")))
            .andExpect(jsonPath("$.data.lines[?(@.itemId == '" + salt + "')].cost").value(hasItem(nullValue())))
            .andExpect(jsonPath("$.data.materialCost").value(6.0))
            .andExpect(jsonPath("$.data.costComplete").value(false))
            .andExpect(jsonPath("$.data.costPerUnit").value(nullValue()));

        // D+: later prices cannot fill a missing price or change the price at the original completion time.
        call(put("/items/" + salt), "{\"unitCost\":1}").andExpect(status().isOk());
        call(put("/items/" + flour), "{\"unitCost\":9}").andExpect(status().isOk());
        call(get("/production-runs/" + runId + "/cost"))
            .andExpect(jsonPath("$.data.materialCost").value(6.0))
            .andExpect(jsonPath("$.data.costComplete").value(false))
            .andExpect(jsonPath("$.data.outputQuantity").value(4.0))
            .andExpect(jsonPath("$.data.costPerUnit").value(nullValue()));

        mockMvc.perform(get("/production-runs/" + runId + "/cost")
                .header("Authorization", "Bearer " + jwtProvider.generateAccessToken("run-cost-outsider")))
            .andExpect(status().isForbidden());
    }

    @Test
    void usageIsComparedWithTheBomScaledToWhatWasMade() throws Exception {
        String product = item(null, "unit_ea");
        String flour = item("2");
        String salt = item(null);
        String yeast = item("10");
        String bomId = id(call(post("/boms"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"targetItemId\":\"" + product
            + "\",\"bomName\":\"Loaf\",\"baseQuantity\":10,\"baseUnit\":\"ea\"}").andExpect(status().isOk()), "bomId");
        call(post("/boms/" + bomId + "/lines"), "{\"childItemId\":\"" + flour + "\",\"quantity\":5,\"unit\":\"kg\"}")
            .andExpect(status().isOk());
        call(post("/boms/" + bomId + "/lines"), "{\"childItemId\":\"" + salt + "\",\"quantity\":200,\"unit\":\"g\"}")
            .andExpect(status().isOk());
        call(post("/boms/" + bomId + "/submit")).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/approve")).andExpect(status().isOk());
        String runId = id(call(post("/production-runs/start"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowId\":\""
            + DEMO_WORKFLOW + "\",\"bomId\":\"" + bomId + "\",\"plannedOutputQty\":20}").andExpect(status().isOk()), "productionRunId");

        // 20 planned: 10 kg flour and 0.4 kg salt. Yeast is not in the BOM.
        record(runId, stock(flour, "50"), flour, "11", "kg");
        record(runId, stock(salt, "5"), salt, "400", "g");
        record(runId, stock(yeast, "1"), yeast, "0.1", "kg");
        call(get("/production-runs/" + runId + "/material-usage"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.basisIsActual").value(false))
            .andExpect(jsonPath("$.data.basisQuantity").value(20.0))
            .andExpect(jsonPath("$.data.lines[0].itemId").value(flour))
            .andExpect(jsonPath("$.data.lines[0].standard").value(10.0))
            .andExpect(jsonPath("$.data.lines[0].variance").value(1.0))
            .andExpect(jsonPath("$.data.lines[0].variancePercent").value(10.0));

        // Only 16 made: the standard shrinks to 8 kg flour and 0.32 kg salt.
        call(post("/production-runs/" + runId + "/finish"), "{\"actualOutputQty\":16}").andExpect(status().isOk());
        call(get("/production-runs/" + runId + "/material-usage"))
            .andExpect(jsonPath("$.data.bomId").value(bomId))
            .andExpect(jsonPath("$.data.basisIsActual").value(true))
            .andExpect(jsonPath("$.data.basisQuantity").value(16.0))
            .andExpect(jsonPath("$.data.lines.length()").value(3))
            .andExpect(jsonPath("$.data.lines[0].planned").value(10.0))
            .andExpect(jsonPath("$.data.lines[0].standard").value(8.0))
            .andExpect(jsonPath("$.data.lines[0].actual").value(11.0))
            .andExpect(jsonPath("$.data.lines[0].variancePercent").value(37.5))
            .andExpect(jsonPath("$.data.lines[0].varianceCost").value(6.0))
            .andExpect(jsonPath("$.data.lines[1].itemId").value(salt))
            .andExpect(jsonPath("$.data.lines[1].standard").value(0.32))
            .andExpect(jsonPath("$.data.lines[1].actual").value(0.4))
            .andExpect(jsonPath("$.data.lines[1].variancePercent").value(25.0))
            .andExpect(jsonPath("$.data.lines[1].varianceCost").value(nullValue()))
            .andExpect(jsonPath("$.data.lines[2].itemId").value(yeast))
            .andExpect(jsonPath("$.data.lines[2].inBom").value(false))
            .andExpect(jsonPath("$.data.lines[2].standard").value(0.0))
            .andExpect(jsonPath("$.data.lines[2].variancePercent").value(nullValue()))
            .andExpect(jsonPath("$.data.lines[2].varianceCost").value(1.0))
            // Salt's extra has no price, so the 6 + 1 is marked incomplete.
            .andExpect(jsonPath("$.data.varianceCost").value(7.0))
            .andExpect(jsonPath("$.data.varianceCostComplete").value(false));
    }

    @Test
    void finishingWithZeroOutputUsesZeroAsTheMaterialUsageBasis() throws Exception {
        String product = item(null, "unit_ea");
        String material = item("2");
        String bomId = id(call(post("/boms"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"targetItemId\":\"" + product
            + "\",\"bomName\":\"Zero output\",\"baseQuantity\":10,\"baseUnit\":\"ea\"}")
            .andExpect(status().isOk()), "bomId");
        call(post("/boms/" + bomId + "/lines"), "{\"childItemId\":\"" + material + "\",\"quantity\":5,\"unit\":\"kg\"}")
            .andExpect(status().isOk());
        call(post("/boms/" + bomId + "/submit")).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/approve")).andExpect(status().isOk());
        String runId = id(call(post("/production-runs/start"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowId\":\""
            + DEMO_WORKFLOW + "\",\"bomId\":\"" + bomId + "\",\"plannedOutputQty\":20}")
            .andExpect(status().isOk()), "productionRunId");
        record(runId, stock(material, "20"), material, "3", "kg");
        call(get("/production-runs/" + runId + "/material-usage"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.basisIsActual").value(false))
            .andExpect(jsonPath("$.data.lines[0].standard").value(10.0));
        call(post("/production-runs/" + runId + "/finish"), "{\"actualOutputQty\":0}").andExpect(status().isOk());
        call(get("/production-runs/" + runId + "/material-usage"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.basisIsActual").value(true))
            .andExpect(jsonPath("$.data.basisQuantity").value(0.0))
            .andExpect(jsonPath("$.data.lines[0].planned").value(10.0))
            .andExpect(jsonPath("$.data.lines[0].standard").value(0.0))
            .andExpect(jsonPath("$.data.lines[0].actual").value(3.0))
            .andExpect(jsonPath("$.data.lines[0].variance").value(3.0))
            .andExpect(jsonPath("$.data.lines[0].variancePercent").value(nullValue()))
            .andExpect(jsonPath("$.data.varianceCost").value(6.0))
            .andExpect(jsonPath("$.data.varianceCostComplete").value(true));
        call(get("/production-runs/" + runId + "/cost"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.materialCost").value(6.0))
            .andExpect(jsonPath("$.data.costPerUnit").value(nullValue()));
    }

    // ---- helpers ----

    private String record(String runId, String inventoryId, String itemId, String qty, String unit) throws Exception {
        return id(call(post("/production-runs/" + runId + "/items"), "{\"inventoryId\":\"" + inventoryId + "\",\"itemId\":\"" + itemId
            + "\",\"direction\":\"input\",\"plannedQty\":" + qty + ",\"actualQty\":" + qty + ",\"unit\":\"" + unit + "\"}")
            .andExpect(status().isOk()), "productionRunItemId");
    }

    private String stock(String itemId, String quantity) throws Exception {
        return id(call(post("/inventories"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemId\":\"" + itemId + "\",\"quantity\":"
            + quantity + ",\"location\":\"RC-" + suffix().substring(0, 6) + "\"}").andExpect(status().isOk()), "inventoryId");
    }

    private String item(String unitCost) {
        return item(unitCost, "unit_kg");
    }

    private String item(String unitCost, String unitId) {
        String id = "itm-rcost-" + suffix();
        Item item = new Item();
        item.setItemId(id);
        item.setProjectId(DEMO_PROJECT);
        item.setItemCode(id.toUpperCase());
        item.setItemName(id);
        item.setItemType("material");
        item.setResourceCategory("material");
        item.setUnitId(unitId);
        item.setItemStatus("active");
        item.setLotManageYn("N");
        item.setUnitCost(unitCost == null ? null : new BigDecimal(unitCost));
        item.setDeletedYn("N");
        itemRepository.save(item);
        return id;
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String id(ResultActions result, String field) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).path("data").path(field).asText();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
