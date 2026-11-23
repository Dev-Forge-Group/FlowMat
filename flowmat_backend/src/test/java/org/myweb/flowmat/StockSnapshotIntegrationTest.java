package org.myweb.flowmat;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.catalog.domain.entity.Item;
import org.myweb.flowmat.domain.catalog.repository.ItemRepository;
import org.myweb.flowmat.domain.inventory.domain.entity.Inventory;
import org.myweb.flowmat.domain.inventory.repository.InventoryRepository;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Stock at a past moment (docs/domain/stock-ledger.md "과거 시점 재고") against real Postgres. */
@AutoConfigureMockMvc
class StockSnapshotIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ItemRepository itemRepository;
    @Autowired private InventoryRepository inventoryRepository;

    @Test
    void eachRecordShowsWhatItsLastMovementLeftAtThatMoment() throws Exception {
        String flour = item("2");
        String beforeAll = now();
        String shelf = stock(flour, "10");
        String afterReceipt = now();
        move(shelf, "issue", "4");
        move(shelf, "reserve", "1");
        String transfer = call(post("/inventory-transfers"), "{\"fromInventoryId\":\"" + shelf + "\",\"toLocation\":\"SNAP-B-"
            + suffix().substring(0, 6) + "\",\"quantity\":2,\"requestId\":\"" + UUID.randomUUID() + "\"}")
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String moved = objectMapper.readTree(transfer).path("data").path("in").path("inventoryId").asText();
        String untouched = unmovedRecord(flour, "7");

        String shelfRow = "$.data.rows[?(@.inventoryId == '" + shelf + "')]";
        String movedRow = "$.data.rows[?(@.inventoryId == '" + moved + "')]";
        String untouchedRow = "$.data.rows[?(@.inventoryId == '" + untouched + "')]";

        // Just after the receipt: 10 on the shelf, nothing moved yet, the unmoved record not created yet.
        snapshot(afterReceipt)
            .andExpect(status().isOk())
            .andExpect(jsonPath(shelfRow + ".quantity").value(hasItem(10.0)))
            .andExpect(jsonPath(shelfRow + ".reservedQuantity").value(hasItem(0.0)))
            .andExpect(jsonPath(shelfRow + ".value").value(hasItem(20.0)))
            .andExpect(jsonPath("$.data.rows[*].inventoryId").value(not(hasItem(moved))))
            .andExpect(jsonPath("$.data.rows[*].inventoryId").value(not(hasItem(untouched))));

        // Now: 10 - 4 - 2 moved = 4 with 1 reserved, 2 at the other location, and 7 that never moved.
        snapshot(now())
            .andExpect(jsonPath(shelfRow + ".quantity").value(hasItem(4.0)))
            .andExpect(jsonPath(shelfRow + ".reservedQuantity").value(hasItem(1.0)))
            .andExpect(jsonPath(shelfRow + ".fromLedger").value(hasItem(true)))
            .andExpect(jsonPath(movedRow + ".quantity").value(hasItem(2.0)))
            .andExpect(jsonPath(untouchedRow + ".quantity").value(hasItem(7.0)))
            .andExpect(jsonPath(untouchedRow + ".fromLedger").value(hasItem(false)));

        // Before anything was received the item had no stock.
        snapshot(beforeAll).andExpect(jsonPath("$.data.rows[*].itemId").value(not(hasItem(flour))));

        call(get("/inventory-snapshots").param("projectId", DEMO_PROJECT)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/inventory-snapshots").param("projectId", DEMO_PROJECT).param("at", now())
                .header("Authorization", "Bearer " + jwtProvider.generateAccessToken("snapshot-outsider")))
            .andExpect(status().isForbidden());
    }

    // ---- helpers ----

    private ResultActions snapshot(String at) throws Exception {
        return call(get("/inventory-snapshots").param("projectId", DEMO_PROJECT).param("at", at));
    }

    private static String now() {
        return OffsetDateTime.now(ZoneOffset.UTC).toString();
    }

    /** A record written straight to the table, with no movement behind it (like rows from before the ledger). */
    private String unmovedRecord(String itemId, String quantity) {
        Inventory row = new Inventory();
        row.setInventoryId("inv-snap-" + suffix());
        row.setProjectId(DEMO_PROJECT);
        row.setItemId(itemId);
        row.setQuantity(new BigDecimal(quantity));
        row.setReservedQuantity(BigDecimal.ZERO);
        row.setAvailableQuantity(new BigDecimal(quantity));
        row.setInventoryStatus("available");
        row.setLocation("SNAP-C-" + suffix().substring(0, 6));
        row.setMinThreshold(BigDecimal.ZERO);
        row.setCreatedBy(DEMO_OWNER);
        row.setDeletedYn("N");
        inventoryRepository.save(row);
        return row.getInventoryId();
    }

    private void move(String inventoryId, String type, String quantity) throws Exception {
        call(post("/inventory-transactions"), "{\"inventoryId\":\"" + inventoryId + "\",\"transactionType\":\"" + type
            + "\",\"quantity\":" + quantity + ",\"requestId\":\"" + UUID.randomUUID() + "\"}").andExpect(status().isOk());
    }

    private String stock(String itemId, String quantity) throws Exception {
        return id(call(post("/inventories"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemId\":\"" + itemId + "\",\"quantity\":"
            + quantity + ",\"location\":\"SNAP-A-" + suffix().substring(0, 6) + "\"}").andExpect(status().isOk()), "inventoryId");
    }

    private String item(String unitCost) {
        String id = "itm-snap-" + suffix();
        Item item = new Item();
        item.setItemId(id);
        item.setProjectId(DEMO_PROJECT);
        item.setItemCode(id.toUpperCase());
        item.setItemName(id);
        item.setItemType("material");
        item.setResourceCategory("material");
        item.setUnitId("unit_kg");
        item.setItemStatus("active");
        item.setLotManageYn("N");
        item.setUnitCost(new BigDecimal(unitCost));
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

    private String id(ResultActions result, String field) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).path("data").path(field).asText();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
