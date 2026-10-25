package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** A cancelled run input can go back to its work order (docs/domain/stock-allocation.md R2, R4), on real Postgres. */
@AutoConfigureMockMvc
class RunInputReallocationIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void aCancelledInputIsAllocatedToItsOrderAgainOnlyWhenAsked() throws Exception {
        String tag = tag();
        String loaf = item("RA-LOAF-" + tag, "unit_ea");
        String flour = item("RA-FLOUR-" + tag, "unit_kg");
        String row = stock(flour, 10, "RA-A-" + tag);
        String order = order("Reallocate " + tag, loaf);
        call(post("/work-orders/" + order + "/allocations"), json(Map.of("lines", List.of(Map.of("itemId", flour, "quantity", 6)))))
            .andExpect(status().isOk());
        String runId = run(order);
        String four = itemId(input(runId, flour, row, 4));
        held(row, 6, 2);

        // Refused before anything changes: an output, or an input of a run with no work order.
        String made = itemId(call(post("/production-runs/" + runId + "/items"), json(Map.of("direction", "output", "itemId", loaf,
            "plannedQty", 1, "actualQty", 1, "unit", "ea"))));
        cancel(runId, made, true).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("Only an input")));
        call(get("/production-runs/" + runId + "/items"))
            .andExpect(jsonPath("$.data[?(@.productionRunItemId == '" + made + "')].cancelled").value(false));
        String loose = run(null);
        String one = itemId(input(loose, flour, row, 1));
        cancel(loose, one, true).andExpect(status().isBadRequest());
        held(row, 5, 2);
        cancel(loose, one, false).andExpect(status().isOk());
        held(row, 6, 2);

        // Asked: the 4 kg come back as a new allocation of the order, so the record holds for it what it did before the input.
        cancel(runId, four, true).andExpect(status().isOk()).andExpect(jsonPath("$.data.cancelled").value(true));
        held(row, 10, 6);
        call(get("/work-orders/" + order + "/allocations"))
            .andExpect(jsonPath("$.data.allocations.length()").value(2))
            .andExpect(jsonPath("$.data.allocations[0].consumedQuantity").value(4))
            .andExpect(jsonPath("$.data.allocations[0].remaining").value(2))
            .andExpect(jsonPath("$.data.allocations[1].inventoryId").value(row))
            .andExpect(jsonPath("$.data.allocations[1].quantity").value(4))
            .andExpect(jsonPath("$.data.allocations[1].remaining").value(4))
            .andExpect(jsonPath("$.data.allocations[1].status").value("open"));
        // The same order's run takes it again first; another order cannot.
        String two = itemId(input(runId, flour, row, 2));
        held(row, 8, 4);
        // Not asked: the stock comes back free, as before (R2).
        cancel(runId, two, false).andExpect(status().isOk());
        held(row, 10, 4);
        cancel(runId, two, true).andExpect(status().isConflict());
        callAs("unrelated-user", post("/production-runs/" + runId + "/items/" + four + "/cancel")
            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\",\"reallocate\":true}")).andExpect(status().isForbidden());
    }

    private ResultActions cancel(String runId, String itemId, boolean reallocate) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("reason", "Wrong LOT");
        if (reallocate) body.put("reallocate", true);
        return call(post("/production-runs/" + runId + "/items/" + itemId + "/cancel"), json(body));
    }

    private void held(String inventoryId, int quantity, int reserved) throws Exception {
        call(get("/inventories/" + inventoryId)).andExpect(jsonPath("$.data.quantity").value(quantity))
            .andExpect(jsonPath("$.data.reservedQuantity").value(reserved));
    }

    private ResultActions input(String runId, String itemId, String inventoryId, int quantity) throws Exception {
        return call(post("/production-runs/" + runId + "/items"), json(Map.of("direction", "input", "itemId", itemId,
            "inventoryId", inventoryId, "plannedQty", quantity, "actualQty", quantity, "unit", "kg")));
    }

    private String itemId(ResultActions result) throws Exception {
        return id(result, "productionRunItemId");
    }

    private String run(String orderId) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW, "plannedOutputQty", 1));
        if (orderId != null) body.put("workOrderId", orderId);
        return id(call(post("/production-runs/start"), json(body)), "productionRunId");
    }

    private String order(String title, String itemId) throws Exception {
        String orderId = id(call(post("/work-orders"), json(Map.of("projectId", DEMO_PROJECT, "workOrderTitle", title,
            "workflowId", DEMO_WORKFLOW, "targetItemId", itemId, "targetQuantity", 20))), "workOrderId");
        call(post("/work-orders/" + orderId + "/approve")).andExpect(status().isOk());
        return orderId;
    }

    private String stock(String itemId, int quantity, String location) throws Exception {
        return id(call(post("/inventories"), json(Map.of("projectId", DEMO_PROJECT, "itemId", itemId, "quantity", quantity,
            "location", location))), "inventoryId");
    }

    private String item(String code, String unitId) throws Exception {
        return id(call(post("/items"), json(Map.of("projectId", DEMO_PROJECT, "itemCode", code, "itemName", code.toLowerCase(),
            "itemType", "material", "unitId", unitId))), "itemId");
    }

    private static String tag() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String id(ResultActions result, String field) throws Exception {
        JsonNode body = objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return body.path("data").path(field).asText();
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return callAs(DEMO_OWNER, request);
    }

    private ResultActions callAs(String userId, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(userId)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
