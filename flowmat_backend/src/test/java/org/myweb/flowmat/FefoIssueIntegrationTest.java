package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Issuing an item's stock from its LOTs, first-expiring first (docs/domain/lot-expiry.md), against real Postgres. */
@AutoConfigureMockMvc
class FefoIssueIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void stockIsIssuedSoonestFirstOnceEvenWhenRetried() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String item = data(send(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", "FI-" + tag, "itemName", "flour",
            "unitId", "unit_kg", "lotManageYn", "Y"))).path("itemId").asText();
        String late = stock(item, "LATE-" + tag, LocalDate.now().plusDays(60));
        String gone = stock(item, "GONE-" + tag, LocalDate.now().minusDays(1));
        String soon = stock(item, "SOON-" + tag, LocalDate.now().plusDays(10));

        String requestId = UUID.randomUUID().toString();
        Map<String, Object> body = new HashMap<>(Map.of("projectId", DEMO_PROJECT, "itemId", item, "quantity", 7000, "unit", "g",
            "note", "order 42", "requestId", requestId));
        send(post("/inventories/issue-fefo"), body)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.quantity").value(7.0))
            .andExpect(jsonPath("$.data.unit").value("kg"))
            .andExpect(jsonPath("$.data.lines.length()").value(2))
            .andExpect(jsonPath("$.data.lines[0].inventoryId").value(soon))
            .andExpect(jsonPath("$.data.lines[0].quantity").value(5.0))
            .andExpect(jsonPath("$.data.lines[1].inventoryId").value(late))
            .andExpect(jsonPath("$.data.lines[1].quantity").value(2.0))
            .andExpect(jsonPath("$.data.lines[1].quantityAfter").value(3.0));
        // The same request again returns the first result and moves nothing.
        send(post("/inventories/issue-fefo"), body)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lines.length()").value(2));
        quantity(soon, 0.0);
        quantity(late, 3.0);
        quantity(gone, 5.0);
        mockMvc.perform(get("/inventory-transactions").param("inventoryId", late).header("Authorization", bearer()))
            .andExpect(jsonPath("$.data[0].referenceType").value("fefo_issue"))
            .andExpect(jsonPath("$.data[0].note").value("order 42"));

        // More than the unexpired LOTs hold: refused, nothing issued.
        send(post("/inventories/issue-fefo"), Map.of("projectId", DEMO_PROJECT, "itemId", item, "quantity", 4,
                "requestId", UUID.randomUUID().toString()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("Only 3 kg")));
        quantity(late, 3.0);

        // The first request's key for another item: refused rather than answered with the first item's lines.
        String other = data(send(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", "FJ-" + tag, "itemName", "rye",
            "unitId", "unit_kg", "lotManageYn", "Y"))).path("itemId").asText();
        send(post("/inventories/issue-fefo"), Map.of("projectId", DEMO_PROJECT, "itemId", other, "quantity", 1, "requestId", requestId))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("different item")));
    }

    @Test
    void stockIsReservedSoonestFirstAndLaterIssuesLeaveItAlone() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String item = data(send(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", "FR-" + tag, "itemName", "sugar",
            "unitId", "unit_kg", "lotManageYn", "Y"))).path("itemId").asText();
        String late = stock(item, "RLATE-" + tag, LocalDate.now().plusDays(60));
        stock(item, "RGONE-" + tag, LocalDate.now().minusDays(1));
        String soon = stock(item, "RSOON-" + tag, LocalDate.now().plusDays(10));

        String requestId = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("projectId", DEMO_PROJECT, "itemId", item, "quantity", 7, "action", "reserve",
            "note", "order 43", "requestId", requestId);
        JsonNode reserved = data(send(post("/inventories/issue-fefo"), body));
        assertEquals("reserve", reserved.path("action").asText());
        send(post("/inventories/issue-fefo"), body)
            .andExpect(jsonPath("$.data.lines.length()").value(2))
            .andExpect(jsonPath("$.data.lines[0].inventoryId").value(soon))
            .andExpect(jsonPath("$.data.lines[0].quantity").value(5.0))
            .andExpect(jsonPath("$.data.lines[0].reservedAfter").value(5.0))
            .andExpect(jsonPath("$.data.lines[1].inventoryId").value(late))
            .andExpect(jsonPath("$.data.lines[1].quantity").value(2.0))
            .andExpect(jsonPath("$.data.lines[1].quantityAfter").value(5.0));
        // Held, not taken: on hand stays, and the retry above did not reserve twice.
        mockMvc.perform(get("/inventories/" + late).header("Authorization", bearer()))
            .andExpect(jsonPath("$.data.quantity").value(5.0))
            .andExpect(jsonPath("$.data.reservedQuantity").value(2.0))
            .andExpect(jsonPath("$.data.availableQuantity").value(3.0));
        mockMvc.perform(get("/inventory-transactions").param("inventoryId", soon).header("Authorization", bearer()))
            .andExpect(jsonPath("$.data[0].transactionType").value("reserve"))
            .andExpect(jsonPath("$.data[0].referenceType").value("fefo_reserve"));

        // The reservation's key cannot then issue.
        send(post("/inventories/issue-fefo"), Map.of("projectId", DEMO_PROJECT, "itemId", item, "quantity", 1, "requestId", requestId))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("already used to reserve")));
        // An issue takes only what is not reserved: 3 kg of the later LOT.
        send(post("/inventories/issue-fefo"), Map.of("projectId", DEMO_PROJECT, "itemId", item, "quantity", 4,
                "requestId", UUID.randomUUID().toString()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("Only 3 kg")));

        // Reversing a line gives the hold back.
        send(post("/inventory-transactions/" + reserved.path("lines").path(0).path("inventoryTransactionId").asText() + "/reversal"),
            Map.of("requestId", UUID.randomUUID().toString(), "reason", "order 43 cancelled"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/inventories/" + soon).header("Authorization", bearer()))
            .andExpect(jsonPath("$.data.reservedQuantity").value(0.0));

        send(post("/inventories/issue-fefo"), Map.of("projectId", DEMO_PROJECT, "itemId", item, "quantity", 1, "action", "hold",
                "requestId", UUID.randomUUID().toString()))
            .andExpect(status().isBadRequest());
    }

    @Test
    void twoSplitsAtOnceBothGoThroughOnTheStockLeft() throws Exception {
        for (int round = 0; round < 3; round++) {
            String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            String item = data(send(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", "FC-" + tag, "itemName", "oats",
                "unitId", "unit_kg", "lotManageYn", "Y"))).path("itemId").asText();
            String soon = stock(item, "CSOON-" + tag, LocalDate.now().plusDays(5));
            String late = stock(item, "CLATE-" + tag, LocalDate.now().plusDays(50));
            // Each plans 4 kg; without waiting for the other the second would plan on the soon LOT it no longer has.
            Callable<ResultActions> issue = () -> send(post("/inventories/issue-fefo"), Map.of("projectId", DEMO_PROJECT, "itemId", item,
                "quantity", 4, "requestId", UUID.randomUUID().toString()));
            assertEquals(List.of(200, 200), together(issue, issue));
            quantity(soon, 0.0);
            quantity(late, 2.0);
        }
    }

    /** Runs the calls at the same moment and returns their HTTP statuses in order. */
    private List<Integer> together(Callable<ResultActions> first, Callable<ResultActions> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (Callable<ResultActions> call : List.of(first, second)) {
                results.add(pool.submit(() -> {
                    start.await();
                    return call.call().andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
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
