package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
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

/** One input taken over several LOTs, first-expiring first (docs/domain/lot-expiry.md), against real Postgres. */
@AutoConfigureMockMvc
class RunInputAllocationIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void anInputIsSplitOverUsableLotsSoonestFirstOrNotAtAll() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String item = data(send(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", "AL-" + tag, "itemName", "flour",
            "unitId", "unit_kg", "lotManageYn", "Y"))).path("itemId").asText();
        String late = stock(item, "LATE-" + tag, LocalDate.now().plusDays(60));
        String gone = stock(item, "GONE-" + tag, LocalDate.now().minusDays(1));
        String soon = stock(item, "SOON-" + tag, LocalDate.now().plusDays(10));
        String run = data(send(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "plannedOutputQty", 1))).path("productionRunId").asText();

        // 8000 g is 8 kg: 5 from the LOT that expires first, 3 from the next; the expired LOT is left alone.
        send(post("/production-runs/" + run + "/inputs/fefo"), Map.of("itemId", item, "quantity", 8000, "unit", "g"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].inventoryId").value(soon))
            .andExpect(jsonPath("$.data[0].actualQty").value(5.0))
            .andExpect(jsonPath("$.data[1].inventoryId").value(late))
            .andExpect(jsonPath("$.data[1].actualQty").value(3.0))
            .andExpect(jsonPath("$.data[1].unit").value("kg"));
        quantity(soon, 0.0);
        quantity(late, 2.0);
        quantity(gone, 5.0);

        // More than the usable LOTs hold (2 left): refused, nothing recorded or moved.
        send(post("/production-runs/" + run + "/inputs/fefo"), Map.of("itemId", item, "quantity", 3, "unit", "kg"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("Only 2 kg")));
        quantity(late, 2.0);
        mockMvc.perform(get("/production-runs/" + run + "/items").header("Authorization", bearer()))
            .andExpect(jsonPath("$.data.length()").value(2));

        // Not LOT-tracked: record it directly.
        String plain = data(send(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", "AP-" + tag, "itemName", "salt",
            "unitId", "unit_kg"))).path("itemId").asText();
        send(post("/production-runs/" + run + "/inputs/fefo"), Map.of("itemId", plain, "quantity", 1, "unit", "kg"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("not LOT-tracked")));
    }

    private String stock(String item, String lotNo, LocalDate expiry) throws Exception {
        String lot = data(send(post("/lots"), Map.of("projectId", DEMO_PROJECT, "itemId", item, "lotNo", lotNo,
            "expiryDate", expiry.toString()))).path("lotId").asText();
        return data(send(post("/inventories"), Map.of("projectId", DEMO_PROJECT, "itemId", item, "quantity", 5,
            "location", lotNo, "lotId", lot))).path("inventoryId").asText();
    }

    private void quantity(String inventoryId, double expected) throws Exception {
        mockMvc.perform(get("/inventories/" + inventoryId).header("Authorization", bearer()))
            .andExpect(jsonPath("$.data.quantity").value(expected));
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
