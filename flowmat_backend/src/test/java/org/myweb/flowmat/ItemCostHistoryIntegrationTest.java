package org.myweb.flowmat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

/** Unit cost changes of items (docs/domain/material-cost.md "단가 이력") against real Postgres. */
@AutoConfigureMockMvc
class ItemCostHistoryIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void eachChangeOfAUnitCostIsKept() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String item = id(call(post("/items"), json(Map.of("projectId", DEMO_PROJECT, "itemCode", "COST-" + tag, "itemName", "cost",
            "unitId", "unit_kg", "unitCost", 2))), "itemId");
        String path = "/items/" + item;

        // The same cost again, and a save that leaves the cost out, change nothing.
        call(put(path), json(Map.of("unitCost", 2.0))).andExpect(status().isOk());
        call(put(path), json(Map.of("unitCost", 3.5))).andExpect(status().isOk());
        call(put(path), json(Map.of("itemName", "renamed"))).andExpect(status().isOk());
        call(put(path), json(Map.of("unitCost", 0))).andExpect(status().isOk());

        call(get(path + "/cost-history"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.data[0].previousUnitCost").value(3.5))
            .andExpect(jsonPath("$.data[0].unitCost").value(0))
            .andExpect(jsonPath("$.data[1].previousUnitCost").value(2))
            .andExpect(jsonPath("$.data[1].unitCost").value(3.5))
            .andExpect(jsonPath("$.data[2].previousUnitCost").doesNotExist())
            .andExpect(jsonPath("$.data[2].unitCost").value(2))
            .andExpect(jsonPath("$.data[2].changedBy").value(DEMO_OWNER))
            .andExpect(jsonPath("$.data[2].changedAt").isNotEmpty());

        // Without a cost there is nothing to keep, and 0 is no cost either.
        String plain = id(call(post("/items"), json(Map.of("projectId", DEMO_PROJECT, "itemCode", "COST-PLAIN-" + tag, "itemName", "plain",
            "unitId", "unit_kg"))), "itemId");
        call(put("/items/" + plain), json(Map.of("unitCost", 0))).andExpect(status().isOk());
        call(get("/items/" + plain + "/cost-history")).andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(get(path + "/cost-history").header("Authorization", "Bearer " + jwtProvider.generateAccessToken("cost-outsider")))
            .andExpect(status().isForbidden());
        call(get("/items/no-such-item/cost-history")).andExpect(status().isNotFound());
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
