package org.myweb.flowmat;

import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** A count stamps every record it covers (docs/domain/stock-count.md "마지막 실사") against real Postgres. */
@AutoConfigureMockMvc
class InventoryLastCountedIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void countedRecordsAreStampedEvenWithoutADifference() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String item = data(send(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", "LC-" + tag, "itemName", "lc"))).path("itemId").asText();
        JsonNode right = data(send(post("/inventories"), Map.of("projectId", DEMO_PROJECT, "itemId", item, "quantity", 5, "location", "LC-A-" + tag)));
        JsonNode off = data(send(post("/inventories"), Map.of("projectId", DEMO_PROJECT, "itemId", item, "quantity", 3, "location", "LC-B-" + tag)));
        JsonNode untouched = data(send(post("/inventories"), Map.of("projectId", DEMO_PROJECT, "itemId", item, "quantity", 1, "location", "LC-C-" + tag)));
        mockMvc.perform(get("/inventories/" + right.path("inventoryId").asText()).header("Authorization", bearer()))
            .andExpect(jsonPath("$.data.lastCheckedAt").value(nullValue()));

        send(post("/inventory-counts"), Map.of("projectId", DEMO_PROJECT, "requestId", UUID.randomUUID().toString(), "lines", List.of(
                Map.of("inventoryId", right.path("inventoryId").asText(), "countedQuantity", 5, "expectedQuantity", 5),
                Map.of("inventoryId", off.path("inventoryId").asText(), "countedQuantity", 2, "expectedQuantity", 3))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.adjusted").value(1))
            .andExpect(jsonPath("$.data.unchanged").value(1));

        // Right as it was: stamped, and its version did not move, so an open adjustment still goes through.
        mockMvc.perform(get("/inventories/" + right.path("inventoryId").asText()).header("Authorization", bearer()))
            .andExpect(jsonPath("$.data.lastCheckedAt").value(notNullValue()))
            .andExpect(jsonPath("$.data.lastCheckedBy").value(DEMO_OWNER))
            .andExpect(jsonPath("$.data.version").value(right.path("version").asLong()))
            .andExpect(jsonPath("$.data.quantity").value(5.0));
        mockMvc.perform(get("/inventories/" + off.path("inventoryId").asText()).header("Authorization", bearer()))
            .andExpect(jsonPath("$.data.lastCheckedAt").value(notNullValue()))
            .andExpect(jsonPath("$.data.quantity").value(2.0));
        mockMvc.perform(get("/inventories/" + untouched.path("inventoryId").asText()).header("Authorization", bearer()))
            .andExpect(jsonPath("$.data.lastCheckedAt").value(nullValue()));
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
