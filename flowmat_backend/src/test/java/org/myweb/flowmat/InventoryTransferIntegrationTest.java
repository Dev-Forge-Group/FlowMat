package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.catalog.domain.entity.Item;
import org.myweb.flowmat.domain.catalog.repository.ItemRepository;
import org.myweb.flowmat.domain.inventory.domain.entity.Inventory;
import org.myweb.flowmat.domain.inventory.repository.InventoryRepository;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Moving stock between places (docs/domain/stock-transfer.md) against real Postgres. */
@AutoConfigureMockMvc
class InventoryTransferIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ItemRepository itemRepository;
    @Autowired private InventoryRepository inventoryRepository;

    @Test
    void movingStockKeepsReservationsBehindAndReusesTheDestination() throws Exception {
        String item = item(false);
        String placeA = "WH-A-" + suffix().substring(0, 6);
        String placeB = "WH-B-" + suffix().substring(0, 6);
        String rowA = data(call(post("/inventories"), stock(item, null, placeA, "10"))).path("inventoryId").asText();
        move(rowA, "reserve", "2").andExpect(status().isOk());

        String requestId = UUID.randomUUID().toString();
        JsonNode transfer = data(transfer(rowA, placeB, "5", requestId)
            .andExpect(jsonPath("$.data.out.transactionType").value("transfer_out"))
            .andExpect(jsonPath("$.data.out.quantityDelta").value(-5))
            .andExpect(jsonPath("$.data.in.transactionType").value("transfer_in"))
            .andExpect(jsonPath("$.data.in.quantityDelta").value(5)));
        String transferId = transfer.path("transferId").asText();
        String rowB = transfer.path("in").path("inventoryId").asText();
        call(get("/inventories/" + rowA))
            .andExpect(jsonPath("$.data.quantity").value(5))
            .andExpect(jsonPath("$.data.reservedQuantity").value(2))
            .andExpect(jsonPath("$.data.availableQuantity").value(3));
        call(get("/inventories/" + rowB))
            .andExpect(jsonPath("$.data.quantity").value(5))
            .andExpect(jsonPath("$.data.location").value(placeB));

        // A retry returns the first transfer and moves nothing.
        transfer(rowA, placeB, "5", requestId).andExpect(jsonPath("$.data.transferId").value(transferId));
        call(get("/inventories/" + rowA)).andExpect(jsonPath("$.data.quantity").value(5));

        // Reserved stock stays; the same place is not a move.
        transfer(rowA, placeB, "4", UUID.randomUUID().toString()).andExpect(status().isConflict());
        transfer(rowA, " " + placeA + " ", "1", UUID.randomUUID().toString())
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("already at " + placeA)));

        // Moving back lands in the original record instead of a new one.
        transfer(rowB, placeA, "5", UUID.randomUUID().toString())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.in.inventoryId").value(rowA));
        call(get("/inventories/" + rowA)).andExpect(jsonPath("$.data.quantity").value(10));
        call(get("/inventories/" + rowB)).andExpect(jsonPath("$.data.quantity").value(0));
    }

    @Test
    void aLotKeepsItsLotAndItsQuarantineWhenMoved() throws Exception {
        String item = item(true);
        String lotId = data(call(post("/lots"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemId\":\"" + item
            + "\",\"lotNo\":\"TR-" + suffix() + "\"}")).path("lotId").asText();
        String rowA = data(call(post("/inventories"), stock(item, lotId, "WH-A-" + suffix().substring(0, 6), "8")))
            .path("inventoryId").asText();

        JsonNode transfer = data(transfer(rowA, "WH-C-" + suffix().substring(0, 6), "3", UUID.randomUUID().toString())
            .andExpect(jsonPath("$.data.in.lotId").value(lotId)));
        call(get("/lots/" + lotId)).andExpect(jsonPath("$.data.quantityOnHand").value(8));

        // A transfer is undone by a transfer back, not by reversing one leg.
        call(post("/inventory-transactions/" + transfer.path("out").path("inventoryTransactionId").asText() + "/reversal"),
            "{\"reason\":\"oops\",\"requestId\":\"" + UUID.randomUUID() + "\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("transfer_out transaction cannot be reversed")));

        move(rowA, "quarantine", null).andExpect(status().isOk());
        transfer(rowA, "WH-D-" + suffix().substring(0, 6), "1", UUID.randomUUID().toString()).andExpect(status().isConflict());
    }

    @Test
    void transfersIntoTheSameNewPlaceAtOnceBothLand() throws Exception {
        String lotItem = item(true);
        String lotId = data(call(post("/lots"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemId\":\"" + lotItem
            + "\",\"lotNo\":\"TR-" + suffix().substring(0, 8) + "\"}")).path("lotId").asText();
        String plainItem = item(false);
        for (int round = 0; round < 3; round++) {
            // Same LOT from two places into one new place: without taking turns the second insert hits the unique index.
            String a = data(call(post("/inventories"), stock(lotItem, lotId, "CA-" + suffix(), "5"))).path("inventoryId").asText();
            String b = data(call(post("/inventories"), stock(lotItem, lotId, "CB-" + suffix(), "5"))).path("inventoryId").asText();
            String place = "CD-" + suffix();
            assertEquals(List.of(200, 200), together(() -> transfer(a, place, "1", UUID.randomUUID().toString()),
                () -> transfer(b, place, "2", UUID.randomUUID().toString())));
            assertEquals(0, new BigDecimal("3").compareTo(
                inventoryRepository.findLotStockAt(DEMO_PROJECT, lotItem, lotId, place).orElseThrow().getQuantity()));

            // A non-LOT item has no index to trip, but must still end up with one record there.
            String c = data(call(post("/inventories"), stock(plainItem, null, "CC-" + suffix(), "5"))).path("inventoryId").asText();
            String d = data(call(post("/inventories"), stock(plainItem, null, "CE-" + suffix(), "5"))).path("inventoryId").asText();
            String plainPlace = "CF-" + suffix();
            assertEquals(List.of(200, 200), together(() -> transfer(c, plainPlace, "1", UUID.randomUUID().toString()),
                () -> transfer(d, plainPlace, "2", UUID.randomUUID().toString())));
            List<Inventory> there = inventoryRepository.findStockAt(DEMO_PROJECT, plainItem, plainPlace);
            assertEquals(1, there.size());
            assertEquals(0, new BigDecimal("3").compareTo(there.get(0).getQuantity()));
        }
    }

    @Test
    void addingTheSameLotRecordTwiceAtOnceSaysWhichRecordToUse() throws Exception {
        String lotItem = item(true);
        String lotId = data(call(post("/lots"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemId\":\"" + lotItem
            + "\",\"lotNo\":\"TA-" + suffix().substring(0, 8) + "\"}")).path("lotId").asText();
        for (int round = 0; round < 3; round++) {
            String place = "TA-" + suffix();
            Callable<ResultActions> add = () -> call(post("/inventories"), stock(lotItem, lotId, place, "1"));
            List<MockHttpServletResponse> responses = togetherResponses(add, add);
            List<Integer> statuses = responses.stream().map(MockHttpServletResponse::getStatus).sorted().toList();
            assertEquals(List.of(200, 409), statuses);
            // The loser waited for the winner and got the plain answer, not a unique-index error.
            String refused = responses.stream().filter(r -> r.getStatus() == 409).findFirst().orElseThrow().getContentAsString();
            assertTrue(refused.contains("already has a stock record"), refused);
        }
    }

    @Test
    void aLotEmptiedFromTwoPlacesAtOnceEndsConsumed() throws Exception {
        String lotItem = item(true);
        for (int round = 0; round < 4; round++) {
            String lotId = data(call(post("/lots"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemId\":\"" + lotItem
                + "\",\"lotNo\":\"TC-" + suffix().substring(0, 8) + "\"}")).path("lotId").asText();
            String a = data(call(post("/inventories"), stock(lotItem, lotId, "LA-" + suffix(), "5"))).path("inventoryId").asText();
            String b = data(call(post("/inventories"), stock(lotItem, lotId, "LB-" + suffix(), "5"))).path("inventoryId").asText();
            // Each issue sees only its own record change; without a re-check after commit the LOT stays available.
            assertEquals(List.of(200, 200), together(() -> move(a, "issue", "5"), () -> move(b, "issue", "5")));
            call(get("/lots/" + lotId))
                .andExpect(jsonPath("$.data.quantityOnHand").value(0))
                .andExpect(jsonPath("$.data.lotStatus").value("consumed"));
        }
    }

    /** Runs the calls at the same moment and returns their HTTP statuses in order. */
    private List<Integer> together(Callable<ResultActions> first, Callable<ResultActions> second) throws Exception {
        return togetherResponses(first, second).stream().map(MockHttpServletResponse::getStatus).toList();
    }

    private List<MockHttpServletResponse> togetherResponses(Callable<ResultActions> first, Callable<ResultActions> second)
        throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<MockHttpServletResponse>> results = new ArrayList<>();
            for (Callable<ResultActions> call : List.of(first, second)) {
                results.add(pool.submit(() -> {
                    start.await();
                    return call.call().andReturn().getResponse();
                }));
            }
            start.countDown();
            List<MockHttpServletResponse> responses = new ArrayList<>();
            for (Future<MockHttpServletResponse> result : results) {
                responses.add(result.get(30, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- helpers ----

    private ResultActions transfer(String fromInventoryId, String toLocation, String quantity, String requestId) throws Exception {
        return call(post("/inventory-transfers"), "{\"fromInventoryId\":\"" + fromInventoryId + "\",\"toLocation\":\""
            + toLocation + "\",\"quantity\":" + quantity + ",\"requestId\":\"" + requestId + "\"}");
    }

    private ResultActions move(String inventoryId, String type, String quantity) throws Exception {
        return call(post("/inventory-transactions"), "{\"inventoryId\":\"" + inventoryId + "\",\"transactionType\":\"" + type + "\","
            + (quantity != null ? "\"quantity\":" + quantity + "," : "") + "\"requestId\":\"" + UUID.randomUUID() + "\"}");
    }

    private static String stock(String itemId, String lotId, String location, String quantity) {
        return "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemId\":\"" + itemId + "\",\"quantity\":" + quantity
            + ",\"location\":\"" + location + "\"" + (lotId != null ? ",\"lotId\":\"" + lotId + "\"" : "") + "}";
    }

    private String item(boolean lotTracked) {
        String id = "itm-tr-" + suffix();
        Item item = new Item();
        item.setItemId(id);
        item.setProjectId(DEMO_PROJECT);
        item.setItemCode(id.toUpperCase());
        item.setItemName(id);
        item.setItemType("material");
        item.setResourceCategory("material");
        item.setUnitId("unit_kg");
        item.setItemStatus("active");
        item.setLotManageYn(lotTracked ? "Y" : "N");
        item.setDeletedYn("N");
        itemRepository.save(item);
        return id;
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
