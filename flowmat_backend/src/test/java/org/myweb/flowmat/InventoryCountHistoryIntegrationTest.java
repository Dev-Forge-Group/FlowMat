package org.myweb.flowmat;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
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

/** Past stock counts (docs/domain/stock-count.md "실사 이력") against real Postgres. */
@AutoConfigureMockMvc
class InventoryCountHistoryIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void eachCountListsTheRecordsItChangedNewestFirst() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String flour = id(call(post("/items"), json(Map.of("projectId", DEMO_PROJECT, "itemCode", "CH-F-" + tag, "itemName", "count flour",
            "unitId", "unit_kg", "unitCost", 2))), "itemId");
        String salt = id(call(post("/items"), json(Map.of("projectId", DEMO_PROJECT, "itemCode", "CH-S-" + tag, "itemName", "count salt",
            "unitId", "unit_kg"))), "itemId");
        String flourShelf = id(call(post("/inventories"), json(Map.of("projectId", DEMO_PROJECT, "itemId", flour, "quantity", 10,
            "location", "CH-A-" + tag))), "inventoryId");
        String saltShelf = id(call(post("/inventories"), json(Map.of("projectId", DEMO_PROJECT, "itemId", salt, "quantity", 5,
            "location", "CH-B-" + tag))), "inventoryId");

        Map<String, List<Integer>> firstLines = new LinkedHashMap<>();
        firstLines.put(flourShelf, List.of(8, 10));
        firstLines.put(saltShelf, List.of(7, 5));
        String first = count("month end", firstLines);
        String unchanged = count(null, Map.of(flourShelf, List.of(8, 8)));
        String last = count(null, Map.of(flourShelf, List.of(9, 8)));

        String firstCount = "$.data[?(@.countId == '" + first + "')]";
        String lastCount = "$.data[?(@.countId == '" + last + "')]";
        call(get("/inventory-counts").param("projectId", DEMO_PROJECT))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].countId").value(last))
            .andExpect(jsonPath("$.data[*].countId").value(not(hasItem(unchanged))))
            .andExpect(jsonPath(lastCount + ".adjusted").value(hasItem(1)))
            .andExpect(jsonPath(lastCount + ".increase").value(hasItem(1.0)))
            .andExpect(jsonPath(lastCount + ".valueChange").value(hasItem(2.0)))
            .andExpect(jsonPath(lastCount + ".valueComplete").value(hasItem(true)))
            .andExpect(jsonPath(firstCount + ".adjusted").value(hasItem(2)))
            .andExpect(jsonPath(firstCount + ".increase").value(hasItem(2.0)))
            .andExpect(jsonPath(firstCount + ".decrease").value(hasItem(2.0)))
            // Salt has no unit cost: the value is flour's -4 alone and marked incomplete.
            .andExpect(jsonPath(firstCount + ".valueChange").value(hasItem(-4.0)))
            .andExpect(jsonPath(firstCount + ".valueComplete").value(hasItem(false)))
            .andExpect(jsonPath(firstCount + ".note").value(hasItem("Stock count: month end")))
            .andExpect(jsonPath(firstCount + ".countedBy").value(hasItem(DEMO_OWNER)));

        // Flour costs 5 from now on: today's value follows it, the value at the count keeps the price of that time (CH1).
        call(put("/items/" + flour), json(Map.of("unitCost", 5))).andExpect(status().isOk());
        call(get("/inventory-counts").param("projectId", DEMO_PROJECT))
            .andExpect(status().isOk())
            .andExpect(jsonPath(lastCount + ".valueChange").value(hasItem(5.0)))
            .andExpect(jsonPath(lastCount + ".valueAtCount").value(hasItem(2.0)))
            .andExpect(jsonPath(lastCount + ".valueAtCountComplete").value(hasItem(true)))
            .andExpect(jsonPath(lastCount + ".valueAtCountEstimated").value(hasItem(false)))
            .andExpect(jsonPath(firstCount + ".valueChange").value(hasItem(-10.0)))
            .andExpect(jsonPath(firstCount + ".valueAtCount").value(hasItem(-4.0)))
            // Salt had no unit cost then either.
            .andExpect(jsonPath(firstCount + ".valueAtCountComplete").value(hasItem(false)))
            .andExpect(jsonPath(firstCount + ".valueAtCountEstimated").value(hasItem(false)));

        mockMvc.perform(get("/inventory-counts").param("projectId", DEMO_PROJECT)
                .header("Authorization", "Bearer " + jwtProvider.generateAccessToken("count-history-outsider")))
            .andExpect(status().isForbidden());
    }

    // ---- helpers ----

    /** Counts records, each with [counted, expected]; returns the count's id. */
    private String count(String note, Map<String, List<Integer>> counted) throws Exception {
        List<Map<String, Object>> lines = counted.entrySet().stream()
            .map(entry -> Map.<String, Object>of("inventoryId", entry.getKey(), "countedQuantity", entry.getValue().get(0),
                "expectedQuantity", entry.getValue().get(1)))
            .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", DEMO_PROJECT);
        body.put("requestId", UUID.randomUUID().toString());
        if (note != null) {
            body.put("note", note);
        }
        body.put("lines", lines);
        return id(call(post("/inventory-counts"), json(body)), "countId");
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
