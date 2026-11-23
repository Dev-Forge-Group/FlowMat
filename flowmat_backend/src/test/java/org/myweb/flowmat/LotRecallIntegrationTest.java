package org.myweb.flowmat;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** LOT recall (docs/domain/lot-recall.md) against real Postgres. */
@AutoConfigureMockMvc
class LotRecallIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void aSuspectLotShowsWhatItWentIntoAndCanBeHeldAllTogether() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String raw = item("RC-RAW-" + tag);
        String mid = item("RC-MID-" + tag);
        String product = item("RC-PRD-" + tag);
        String rawLot = lot(raw, "RC-L1-" + tag);
        String midLot = lot(mid, "RC-L2-" + tag);
        String productLot = lot(product, "RC-L3-" + tag);
        String rawStock = stock(raw, rawLot, "20", "RC-A-" + tag);
        String midStock = stock(mid, midLot, "0", "RC-B-" + tag);
        String productStock = stock(product, productLot, "0", "RC-C-" + tag);

        // Raw -> mid in one run, mid -> product in the next; one product unit is sold.
        run(rawStock, raw, "5", midStock, mid, "4");
        run(midStock, mid, "2", productStock, product, "3");
        call(post("/inventory-transactions"), json(Map.of("inventoryId", productStock, "transactionType", "issue", "quantity", 1,
            "requestId", UUID.randomUUID().toString()))).andExpect(status().isOk());

        String midLine = "$.data.lots[?(@.lotId == '" + midLot + "')]";
        String productLine = "$.data.lots[?(@.lotId == '" + productLot + "')]";
        call(get("/lots/" + rawLot + "/recall"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lots.length()").value(3))
            .andExpect(jsonPath("$.data.lots[0].lotId").value(rawLot))
            .andExpect(jsonPath("$.data.lots[0].depth").value(0))
            .andExpect(jsonPath("$.data.lots[0].onHand").value(15.0))
            .andExpect(jsonPath("$.data.lots[0].places[0]").value("RC-A-" + tag + " 15"))
            .andExpect(jsonPath(midLine + ".depth").value(hasItem(1)))
            .andExpect(jsonPath(midLine + ".viaLotNo").value(hasItem("RC-L1-" + tag)))
            .andExpect(jsonPath(midLine + ".onHand").value(hasItem(2.0)))
            .andExpect(jsonPath(productLine + ".depth").value(hasItem(2)))
            .andExpect(jsonPath(productLine + ".onHand").value(hasItem(2.0)))
            .andExpect(jsonPath(productLine + ".issued").value(hasItem(1.0)));

        call(post("/lots/" + rawLot + "/recall/quarantine"), json(Map.of("reason", " "))).andExpect(status().isBadRequest());
        call(post("/lots/" + rawLot + "/recall/quarantine"), json(Map.of("reason", "supplier recall")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.quarantined.length()").value(3))
            .andExpect(jsonPath("$.data.skipped.length()").value(0));
        call(get("/inventories/" + productStock)).andExpect(jsonPath("$.data.inventoryStatus").value("quarantined"));
        call(get("/inventory-transactions").param("projectId", DEMO_PROJECT).param("inventoryId", productStock))
            .andExpect(jsonPath("$.data[0].transactionType").value("quarantine"))
            .andExpect(jsonPath("$.data[0].referenceType").value("lot_recall"))
            .andExpect(jsonPath("$.data[0].referenceId").value(rawLot));
        // Doing it again changes nothing and says why.
        call(post("/lots/" + rawLot + "/recall/quarantine"), json(Map.of("reason", "again")))
            .andExpect(jsonPath("$.data.quarantined.length()").value(0))
            .andExpect(jsonPath("$.data.skipped[0].reason").value("already quarantined"));

        mockMvc.perform(get("/lots/" + rawLot + "/recall")
                .header("Authorization", "Bearer " + jwtProvider.generateAccessToken("recall-outsider")))
            .andExpect(status().isForbidden());
    }

    // ---- helpers ----

    private void run(String inStock, String inItem, String inQty, String outStock, String outItem, String outQty) throws Exception {
        String runId = id(call(post("/production-runs/start"), json(Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "plannedOutputQty", 1))), "productionRunId");
        call(post("/production-runs/" + runId + "/items"), json(Map.of("inventoryId", inStock, "itemId", inItem, "direction", "input",
            "plannedQty", inQty, "actualQty", inQty, "unit", "kg"))).andExpect(status().isOk());
        call(post("/production-runs/" + runId + "/items"), json(Map.of("inventoryId", outStock, "itemId", outItem, "direction", "output",
            "plannedQty", outQty, "actualQty", outQty, "unit", "kg"))).andExpect(status().isOk());
        call(post("/production-runs/" + runId + "/finish"), json(Map.of("actualOutputQty", outQty))).andExpect(status().isOk());
    }

    private String item(String code) throws Exception {
        return id(call(post("/items"), json(Map.of("projectId", DEMO_PROJECT, "itemCode", code, "itemName", code.toLowerCase(),
            "unitId", "unit_kg", "lotManageYn", "Y"))), "itemId");
    }

    private String lot(String itemId, String lotNo) throws Exception {
        return id(call(post("/lots"), json(Map.of("projectId", DEMO_PROJECT, "itemId", itemId, "lotNo", lotNo))), "lotId");
    }

    private String stock(String itemId, String lotId, String quantity, String location) throws Exception {
        return id(call(post("/inventories"), json(Map.of("projectId", DEMO_PROJECT, "itemId", itemId, "lotId", lotId,
            "quantity", quantity, "location", location))), "inventoryId");
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String id(ResultActions result, String field) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
            .path("data").path(field).asText();
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
