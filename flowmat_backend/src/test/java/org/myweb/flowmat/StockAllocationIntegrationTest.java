package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
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

/** Stock allocated to work orders (docs/domain/stock-allocation.md) against real Postgres. */
@AutoConfigureMockMvc
class StockAllocationIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void allocatingReservesStockAndARunOfTheOrderUsesIt() throws Exception {
        String tag = tag();
        String loaf = item("SA-LOAF-" + tag, "unit_ea");
        String flour = item("SA-FLOUR-" + tag, "unit_kg");
        String older = stock(flour, 6, "SA-A-" + tag);
        String newer = stock(flour, 10, "SA-B-" + tag);
        String order = order("Allocate " + tag, loaf, null, true);
        String draft = order("Draft " + tag, loaf, null, false);

        allocate(draft, lines(flour, 1)).andExpect(status().isConflict());
        allocate(order, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("give the lines")));

        // 8 kg: the older record's 6 first, then 2 of the newer one.
        allocate(order, lines(flour, 8))
            .andExpect(jsonPath("$.data.allocations.length()").value(2))
            .andExpect(jsonPath("$.data.allocations[0].inventoryId").value(older))
            .andExpect(jsonPath("$.data.allocations[0].quantity").value(6))
            .andExpect(jsonPath("$.data.allocations[1].quantity").value(2))
            .andExpect(jsonPath("$.data.plan[0].allocatedNow").value(8))
            .andExpect(jsonPath("$.data.plan[0].shortage").value(0));
        reserved(older, 6);
        reserved(newer, 2);
        // Asking again for the same allocates nothing more; asking for 20 takes the rest and says what is short.
        allocate(order, lines(flour, 8)).andExpect(jsonPath("$.data.plan[0].allocatedBefore").value(8))
            .andExpect(jsonPath("$.data.plan[0].allocatedNow").value(0));
        JsonNode more = data(allocate(order, lines(flour, 20))
            .andExpect(jsonPath("$.data.plan[0].allocatedNow").value(8))
            .andExpect(jsonPath("$.data.plan[0].shortage").value(4)));
        String last = more.path("allocations").get(2).path("allocationId").asText();
        call(post("/work-orders/" + order + "/allocations/" + last + "/release"))
            .andExpect(jsonPath("$.data.allocations[2].status").value("closed"))
            .andExpect(jsonPath("$.data.allocations[2].releasedQuantity").value(8));
        reserved(newer, 2);
        call(post("/work-orders/" + order + "/allocations/" + last + "/release")).andExpect(status().isConflict());

        // Other orders cannot take the allocated stock; a run of this order can.
        String other = order("Other " + tag, loaf, null, true);
        String otherRun = run(other);
        input(otherRun, flour, older, 1).andExpect(status().isConflict());
        String ownRun = run(order);
        input(ownRun, flour, older, 4).andExpect(status().isOk());
        call(get("/work-orders/" + order + "/allocations"))
            .andExpect(jsonPath("$.data.allocations[0].consumedQuantity").value(4))
            .andExpect(jsonPath("$.data.allocations[0].remaining").value(2));
        call(get("/inventories/" + older)).andExpect(jsonPath("$.data.quantity").value(2)).andExpect(jsonPath("$.data.reservedQuantity").value(2));
        // Taking more than the record holds fails whole: the allocation is left as it was.
        input(ownRun, flour, older, 5).andExpect(status().isConflict());
        call(get("/work-orders/" + order + "/allocations")).andExpect(jsonPath("$.data.allocations[0].remaining").value(2));
        input(ownRun, flour, older, 2).andExpect(status().isOk());
        call(get("/work-orders/" + order + "/allocations")).andExpect(jsonPath("$.data.allocations[0].status").value("closed"));

        call(post("/work-orders/" + order + "/allocations/release"))
            .andExpect(jsonPath("$.data.allocations[1].status").value("closed"))
            .andExpect(jsonPath("$.data.allocations[1].releasedQuantity").value(2));
        reserved(newer, 0);
        callAs("unrelated-user", get("/work-orders/" + order + "/allocations")).andExpect(status().isForbidden());
    }

    @Test
    void anOrdersOwnAllocationCountsAsAvailableForIt() throws Exception {
        String tag = tag();
        String bread = item("SA-BREAD-" + tag, "unit_ea");
        String flour = item("SA-FL-" + tag, "unit_kg");
        stock(flour, 8, "SA-C-" + tag);
        String bomId = id(call(post("/boms"), json(Map.of("projectId", DEMO_PROJECT, "targetItemId", bread, "bomName", "Bread " + tag,
            "baseQuantity", 10, "baseUnit", "ea"))), "bomId");
        call(post("/boms/" + bomId + "/lines"), json(Map.of("childItemId", flour, "quantity", 5, "unit", "kg"))).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/submit")).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/approve")).andExpect(status().isOk());
        String order = order("Bread " + tag, bread, bomId, true);

        // 20 breads need 10 kg; 8 are there, so 8 are allocated and 2 are short.
        allocate(order, null)
            .andExpect(jsonPath("$.data.plan[0].needed").value(10))
            .andExpect(jsonPath("$.data.plan[0].allocatedNow").value(8))
            .andExpect(jsonPath("$.data.plan[0].shortage").value(2));
        call(get("/work-orders/" + order + "/readiness"))
            .andExpect(jsonPath("$.data.materials[0].availableQuantity").value(8))
            .andExpect(jsonPath("$.data.materials[0].shortageQuantity").value(2));
        call(get("/material-requirements").param("projectId", DEMO_PROJECT))
            .andExpect(jsonPath("$.data.lines[?(@.itemId == '" + flour + "')].usable").value(hasItem(8.0)))
            .andExpect(jsonPath("$.data.lines[?(@.itemId == '" + flour + "')].shortage").value(hasItem(2.0)));
    }

    @Test
    void completingOrCancellingAnOrderGivesBackWhatIsStillAllocated() throws Exception {
        String tag = tag();
        String loaf = item("SA-ROLL-" + tag, "unit_ea");
        String flour = item("SA-RYE-" + tag, "unit_kg");
        String row = stock(flour, 10, "SA-D-" + tag);

        String cancelled = order("Cancel " + tag, loaf, null, true);
        allocate(cancelled, lines(flour, 3)).andExpect(status().isOk());
        reserved(row, 3);
        call(post("/work-orders/" + cancelled + "/cancel")).andExpect(status().isOk());
        reserved(row, 0);
        call(get("/work-orders/" + cancelled + "/allocations"))
            .andExpect(jsonPath("$.data.allocations[0].status").value("closed"))
            .andExpect(jsonPath("$.data.allocations[0].releasedQuantity").value(3));

        // A completed order gives back what its run did not use.
        String completed = order("Complete " + tag, loaf, null, true);
        allocate(completed, lines(flour, 5)).andExpect(status().isOk());
        String runId = run(completed);
        input(runId, flour, row, 2).andExpect(status().isOk());
        call(post("/production-runs/" + runId + "/finish"), "{}").andExpect(status().isOk());
        call(post("/work-orders/" + completed + "/complete")).andExpect(status().isOk());
        reserved(row, 0);
        call(get("/work-orders/" + completed + "/allocations"))
            .andExpect(jsonPath("$.data.allocations[0].consumedQuantity").value(2))
            .andExpect(jsonPath("$.data.allocations[0].releasedQuantity").value(3))
            .andExpect(jsonPath("$.data.allocations[0].status").value("closed"));
    }

    private ResultActions allocate(String orderId, List<Map<String, Object>> lines) throws Exception {
        return call(post("/work-orders/" + orderId + "/allocations"), lines == null ? "{}" : json(Map.of("lines", lines)));
    }

    private static List<Map<String, Object>> lines(String itemId, int quantity) {
        return List.of(Map.of("itemId", itemId, "quantity", quantity));
    }

    private void reserved(String inventoryId, int quantity) throws Exception {
        call(get("/inventories/" + inventoryId)).andExpect(jsonPath("$.data.reservedQuantity").value(quantity));
    }

    private ResultActions input(String runId, String itemId, String inventoryId, int quantity) throws Exception {
        return call(post("/production-runs/" + runId + "/items"), json(Map.of("direction", "input", "itemId", itemId,
            "inventoryId", inventoryId, "plannedQty", quantity, "actualQty", quantity, "unit", "kg")));
    }

    private String run(String orderId) throws Exception {
        return id(call(post("/production-runs/start"), json(Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "plannedOutputQty", 1, "workOrderId", orderId))), "productionRunId");
    }

    private String order(String title, String itemId, String bomId, boolean approve) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("projectId", DEMO_PROJECT);
        body.put("workOrderTitle", title);
        body.put("workflowId", DEMO_WORKFLOW);
        body.put("targetItemId", itemId);
        body.put("targetQuantity", 20);
        body.put("bomId", bomId);
        String orderId = id(call(post("/work-orders"), json(body)), "workOrderId");
        if (approve) {
            call(post("/work-orders/" + orderId + "/approve")).andExpect(status().isOk());
        }
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

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private String id(ResultActions result, String field) throws Exception {
        return data(result).path(field).asText();
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
