package org.myweb.flowmat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
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

/** Stock lost by why (docs/domain/stock-analysis.md "폐기·손실") against real Postgres. */
@AutoConfigureMockMvc
class StockWasteIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void expiredDefectAndCountLossesAreAddedUpAndReversalsLeftOut() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        // Cream: an expired LOT of 3 kg written off, at 2 a kg.
        String cream = data(send(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", "WC-" + tag, "itemName", "cream",
            "unitId", "unit_kg", "lotManageYn", "Y", "unitCost", 2))).path("itemId").asText();
        String oldLot = data(send(post("/lots"), Map.of("projectId", DEMO_PROJECT, "itemId", cream, "lotNo", "WC-OLD-" + tag,
            "expiryDate", LocalDate.now().minusDays(2).toString()))).path("lotId").asText();
        send(post("/inventories"), Map.of("projectId", DEMO_PROJECT, "itemId", cream, "quantity", 3, "location", "WC-" + tag, "lotId", oldLot))
            .andExpect(status().isOk());
        send(post("/lots/expired/write-off"), Map.of("projectId", DEMO_PROJECT, "lotIds", List.of(oldLot), "closeLots", false,
            "requestId", UUID.randomUUID().toString())).andExpect(status().isOk());

        // Sugar, at 1 a kg: a count finds 8 of 10 (loss 2) and 6 of 5 (a gain, not waste); a defect scraps 1;
        // another count loss of 2 is reversed and does not count.
        String sugar = data(send(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", "WS-" + tag, "itemName", "sugar",
            "unitId", "unit_kg", "unitCost", 1))).path("itemId").asText();
        String shelf = record(sugar, "WS-A-" + tag, 10);
        String bin = record(sugar, "WS-B-" + tag, 5);
        String box = record(sugar, "WS-C-" + tag, 3);
        count(shelf, 8, 10);
        count(bin, 6, 5);
        String undone = data(count(box, 1, 3)).path("lines").get(0).path("inventoryTransactionId").asText();
        send(post("/inventory-transactions/" + undone + "/reversal"), Map.of("requestId", UUID.randomUUID().toString(), "reason", "miscount"))
            .andExpect(status().isOk());
        String defect = data(send(post("/defects"), Map.of("projectId", DEMO_PROJECT, "itemId", sugar, "quantity", 1,
            "defectType", "wet"))).path("defectLogId").asText();
        send(post("/defects/" + defect + "/resolve"), Map.of("actionTaken", "binned", "scrapInventoryId", shelf, "scrapQuantity", 1))
            .andExpect(status().isOk());

        String creamLine = "$.data.lines[?(@.itemId == '" + cream + "')]";
        String sugarLine = "$.data.lines[?(@.itemId == '" + sugar + "')]";
        mockMvc.perform(get("/stock-waste").param("projectId", DEMO_PROJECT).param("days", "7").header("Authorization", bearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath(creamLine + ".expired").value(org.hamcrest.Matchers.hasItem(3.0)))
            .andExpect(jsonPath(creamLine + ".value").value(org.hamcrest.Matchers.hasItem(6.0)))
            .andExpect(jsonPath(sugarLine + ".countLoss").value(org.hamcrest.Matchers.hasItem(2.0)))
            .andExpect(jsonPath(sugarLine + ".defect").value(org.hamcrest.Matchers.hasItem(1.0)))
            .andExpect(jsonPath(sugarLine + ".total").value(org.hamcrest.Matchers.hasItem(3.0)))
            .andExpect(jsonPath(sugarLine + ".value").value(org.hamcrest.Matchers.hasItem(3.0)));
        mockMvc.perform(get("/stock-waste").param("projectId", DEMO_PROJECT).param("days", "0").header("Authorization", bearer()))
            .andExpect(status().isBadRequest());
    }

    private String record(String item, String location, int quantity) throws Exception {
        return data(send(post("/inventories"), Map.of("projectId", DEMO_PROJECT, "itemId", item, "quantity", quantity,
            "location", location))).path("inventoryId").asText();
    }

    private ResultActions count(String inventoryId, int counted, int expected) throws Exception {
        return send(post("/inventory-counts"), Map.of("projectId", DEMO_PROJECT, "requestId", UUID.randomUUID().toString(),
            "lines", List.of(Map.of("inventoryId", inventoryId, "countedQuantity", counted, "expectedQuantity", expected))))
            .andExpect(status().isOk());
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions send(MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)).header("Authorization", bearer()));
    }

    private String bearer() {
        return "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER);
    }
}
