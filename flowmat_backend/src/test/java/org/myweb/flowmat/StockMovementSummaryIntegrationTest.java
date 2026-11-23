package org.myweb.flowmat;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

/** A period's stock movement per item (docs/domain/stock-ledger.md "기간 수불") against real Postgres. */
@AutoConfigureMockMvc
class StockMovementSummaryIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void openingPlusInLessOutGivesTheClosingBalance() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String beforeAll = now();
        String flour = id(call(post("/items"), json(Map.of("projectId", DEMO_PROJECT, "itemCode", "SMS-" + tag, "itemName", "summary flour",
            "unitId", "unit_kg"))), "itemId");
        String shelf = id(call(post("/inventories"), json(Map.of("projectId", DEMO_PROJECT, "itemId", flour, "quantity", 10,
            "location", "SMS-A-" + tag))), "inventoryId");
        String start = now();

        // In the period: 3 issued (then reversed), 5 received, 2 moved to another shelf.
        String issue = id(move(shelf, "issue", "3"), "inventoryTransactionId");
        move(shelf, "receipt", "5");
        call(post("/inventory-transfers"), json(Map.of("fromInventoryId", shelf, "toLocation", "SMS-B-" + tag, "quantity", 2,
            "requestId", UUID.randomUUID().toString()))).andExpect(status().isOk());
        call(post("/inventory-transactions/" + issue + "/reversal"), json(Map.of("requestId", UUID.randomUUID().toString(),
            "reason", "wrong item"))).andExpect(status().isOk());
        String end = now();

        String line = "$.data.lines[?(@.itemId == '" + flour + "')]";
        summary(start, end)
            .andExpect(status().isOk())
            .andExpect(jsonPath(line + ".opening").value(hasItem(10.0)))
            .andExpect(jsonPath(line + ".received").value(hasItem(5.0)))
            .andExpect(jsonPath(line + ".issued").value(hasItem(3.0)))
            .andExpect(jsonPath(line + ".transferred").value(hasItem(0.0)))
            .andExpect(jsonPath(line + ".corrected").value(hasItem(3.0)))
            .andExpect(jsonPath(line + ".closing").value(hasItem(15.0)))
            .andExpect(jsonPath(line + ".unexplained").value(hasItem(0.0)))
            .andExpect(jsonPath(line + ".unit").value(hasItem("kg")));

        // From before the item existed, the first 10 is a receipt too.
        summary(beforeAll, end)
            .andExpect(jsonPath(line + ".opening").value(hasItem(0.0)))
            .andExpect(jsonPath(line + ".received").value(hasItem(15.0)))
            .andExpect(jsonPath(line + ".closing").value(hasItem(15.0)))
            .andExpect(jsonPath(line + ".unexplained").value(hasItem(0.0)));

        summary(end, start).andExpect(status().isBadRequest());
        call(get("/stock-movement-summary").param("projectId", DEMO_PROJECT).param("from", start)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/stock-movement-summary").param("projectId", DEMO_PROJECT).param("from", start).param("to", end)
                .header("Authorization", "Bearer " + jwtProvider.generateAccessToken("summary-outsider")))
            .andExpect(status().isForbidden());
    }

    // ---- helpers ----

    private ResultActions summary(String from, String to) throws Exception {
        return call(get("/stock-movement-summary").param("projectId", DEMO_PROJECT).param("from", from).param("to", to));
    }

    private ResultActions move(String inventoryId, String type, String quantity) throws Exception {
        return call(post("/inventory-transactions"), json(Map.of("inventoryId", inventoryId, "transactionType", type,
            "quantity", quantity, "requestId", UUID.randomUUID().toString()))).andExpect(status().isOk());
    }

    private static String now() {
        return OffsetDateTime.now(ZoneOffset.UTC).toString();
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
