package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Work order readiness (docs/domain/work-order-readiness.md) against real Postgres. */
@AutoConfigureMockMvc
class WorkOrderReadinessIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ItemRepository itemRepository;

    @Test
    void readinessFollowsTheOrderTheBomAndTheStock() throws Exception {
        String product = item("unit_ea", false);
        String flour = item("unit_kg", false);
        String salt = item("unit_kg", true);
        String bomId = data(call(post("/boms"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"targetItemId\":\"" + product
            + "\",\"bomName\":\"Bread\",\"baseQuantity\":100,\"baseUnit\":\"ea\"}").andExpect(status().isOk())).path("bomId").asText();
        call(post("/boms/" + bomId + "/lines"), line(flour, "20")).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/lines"), line(salt, "1")).andExpect(status().isOk());
        String orderId = data(call(post("/work-orders"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"workOrderTitle\":\"Bread\","
            + "\"workflowId\":\"" + DEMO_WORKFLOW + "\",\"targetQuantity\":250,\"bomId\":\"" + bomId + "\"}")
            .andExpect(status().isOk())).path("workOrderId").asText();

        // Draft order and draft BOM: not ready, and says why.
        readiness(orderId)
            .andExpect(jsonPath("$.data.ready").value(false))
            .andExpect(jsonPath("$.data.checks[?(@.code == 'status')].status").value(hasItem("fail")))
            .andExpect(jsonPath("$.data.checks[?(@.code == 'bom')].message").value(hasItem(containsString("approve it first"))));

        call(post("/boms/" + bomId + "/submit")).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/approve")).andExpect(status().isOk());
        call(post("/work-orders/" + orderId + "/approve")).andExpect(status().isOk());

        // 250 ea needs 50 kg flour and 2.5 kg salt. Flour is short; a quarantined salt LOT does not count.
        String flourStock = id(stock(flour, null, "30"), "inventoryId");
        stock(salt, lot(salt), "5");
        String quarantined = id(stock(salt, lot(salt), "10"), "inventoryId");
        move(quarantined, "quarantine", null);
        readiness(orderId)
            .andExpect(jsonPath("$.data.ready").value(false))
            .andExpect(jsonPath("$.data.remainingQuantity").value(250.0))
            .andExpect(jsonPath("$.data.checks[?(@.code == 'materials')].message").value(hasItem(containsString("need 50 kg, available 30"))))
            .andExpect(jsonPath("$.data.materials[?(@.itemId == '" + flour + "')].shortageQuantity").value(hasItem(20.0)))
            .andExpect(jsonPath("$.data.materials[?(@.itemId == '" + salt + "')].availableQuantity").value(hasItem(5.0)))
            .andExpect(jsonPath("$.data.materials[?(@.itemId == '" + salt + "')].usableLots").value(hasItem(1)));

        // Receiving the missing flour makes it ready.
        move(flourStock, "receipt", "20");
        readiness(orderId)
            .andExpect(jsonPath("$.data.ready").value(true))
            .andExpect(jsonPath("$.data.checks[?(@.code == 'materials')].status").value(hasItem("ok")));

        // A finished run of 200 leaves 50 to produce, so only 10 kg flour is needed now.
        String runId = data(call(post("/production-runs/start"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowId\":\""
            + DEMO_WORKFLOW + "\",\"plannedOutputQty\":200,\"workOrderId\":\"" + orderId + "\"}").andExpect(status().isOk()))
            .path("productionRunId").asText();
        call(post("/production-runs/" + runId + "/finish"), "{\"actualOutputQty\":200}").andExpect(status().isOk());
        readiness(orderId)
            .andExpect(jsonPath("$.data.remainingQuantity").value(50.0))
            .andExpect(jsonPath("$.data.materials[?(@.itemId == '" + flour + "')].requiredQuantity").value(hasItem(10.0)));
    }

    @Test
    void anOrderWithoutBomOrQuantityOnlyWarns() throws Exception {
        String orderId = data(call(post("/work-orders"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"workOrderTitle\":\"Loose\","
            + "\"workflowId\":\"" + DEMO_WORKFLOW + "\"}").andExpect(status().isOk())).path("workOrderId").asText();
        call(post("/work-orders/" + orderId + "/approve")).andExpect(status().isOk());

        readiness(orderId)
            .andExpect(jsonPath("$.data.ready").value(true))
            .andExpect(jsonPath("$.data.checks[?(@.code == 'bom')].status").value(hasItem("warn")))
            .andExpect(jsonPath("$.data.checks[?(@.code == 'quantity')].status").value(hasItem("warn")))
            .andExpect(jsonPath("$.data.materials.length()").value(0));
        // Outside the project nothing is shown.
        mockMvc.perform(get("/work-orders/" + orderId + "/readiness")
                .header("Authorization", "Bearer " + jwtProvider.generateAccessToken("readiness-outsider")))
            .andExpect(status().isForbidden());
    }

    // ---- helpers ----

    private ResultActions readiness(String orderId) throws Exception {
        return call(get("/work-orders/" + orderId + "/readiness")).andExpect(status().isOk());
    }

    private static String line(String itemId, String quantity) {
        return "{\"childItemId\":\"" + itemId + "\",\"quantity\":" + quantity + ",\"unit\":\"kg\"}";
    }

    private String lot(String itemId) throws Exception {
        return id(call(post("/lots"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemId\":\"" + itemId + "\",\"lotNo\":\"RD-"
            + suffix() + "\"}").andExpect(status().isOk()), "lotId");
    }

    private ResultActions stock(String itemId, String lotId, String quantity) throws Exception {
        return call(post("/inventories"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemId\":\"" + itemId + "\",\"quantity\":"
            + quantity + ",\"location\":\"WH-" + suffix().substring(0, 6) + "\"" + (lotId != null ? ",\"lotId\":\"" + lotId + "\"" : "")
            + "}").andExpect(status().isOk());
    }

    private void move(String inventoryId, String type, String quantity) throws Exception {
        call(post("/inventory-transactions"), "{\"inventoryId\":\"" + inventoryId + "\",\"transactionType\":\"" + type + "\","
            + (quantity != null ? "\"quantity\":" + quantity + "," : "") + "\"requestId\":\"" + UUID.randomUUID() + "\"}")
            .andExpect(status().isOk());
    }

    private String item(String unitId, boolean lotTracked) {
        String id = "itm-rdy-" + suffix();
        Item item = new Item();
        item.setItemId(id);
        item.setProjectId(DEMO_PROJECT);
        item.setItemCode(id.toUpperCase());
        item.setItemName(id);
        item.setItemType("material");
        item.setResourceCategory("material");
        item.setUnitId(unitId);
        item.setItemStatus("active");
        item.setLotManageYn(lotTracked ? "Y" : "N");
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

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    private String id(ResultActions result, String field) throws Exception {
        return data(result).path(field).asText();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
