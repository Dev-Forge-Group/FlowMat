package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class StockAllocationItemIdIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtProvider jwtProvider;

    @ParameterizedTest(name = "invalid allocation item identifier case {index}")
    @ValueSource(strings = {"\u0000", "\u0000extra", "\ud800", "\udc00"})
    void rejectsInvalidItemIdentifiersBeforeQueryingOrReservingStock(String suffix) throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String item = data(call(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", "ALLOC-ID-" + tag,
            "itemName", "Allocation identifier " + tag, "unitId", "unit_ea"))).path("itemId").asText();
        String stock = data(call(post("/inventories"), Map.of("projectId", DEMO_PROJECT, "itemId", item,
            "quantity", 10, "location", "ALLOC-ID-" + tag))).path("inventoryId").asText();
        String order = data(call(post("/work-orders"), Map.of("projectId", DEMO_PROJECT,
            "workOrderTitle", "Allocation identifier " + tag, "targetQuantity", 1))).path("workOrderId").asText();
        call(post("/work-orders/" + order + "/approve")).andExpect(status().isOk());

        call(post("/work-orders/" + order + "/allocations"), Map.of("lines", List.of(
            Map.of("itemId", item, "quantity", 1), Map.of("itemId", item + suffix, "quantity", 2))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("lines.itemId")));
        call(get("/work-orders/" + order + "/allocations")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.allocations", hasSize(0)));
        call(get("/inventories/" + stock)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.quantity").value(10.0))
            .andExpect(jsonPath("$.data.reservedQuantity").value(0.0));

        call(post("/work-orders/" + order + "/allocations"), Map.of("lines", List.of(
            Map.of("itemId", " " + item + " ", "quantity", 2)))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.plan[0].itemId").value(item));
        call(get("/inventories/" + stock)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.quantity").value(10.0))
            .andExpect(jsonPath("$.data.reservedQuantity").value(2.0));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writer().with(JsonWriteFeature.ESCAPE_NON_ASCII).writeValueAsString(body)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
            .path("data");
    }
}
