package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.catalog.domain.entity.Item;
import org.myweb.flowmat.domain.catalog.repository.ItemRepository;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Stock counts (docs/domain/stock-count.md) against real Postgres. */
@AutoConfigureMockMvc
class InventoryCountIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ItemRepository itemRepository;

    @Test
    void aCountAdjustsEveryDifferenceTogetherAndOnlyOnce() throws Exception {
        String item = item();
        String rowA = stock(item, "10");
        String rowB = stock(item, "5");
        String rowC = stock(item, "7");
        String requestId = UUID.randomUUID().toString();
        String body = count(requestId, line(rowA, "8", "10") + "," + line(rowB, "5", "5") + "," + line(rowC, "9", "7"));

        String countId = id(call(post("/inventory-counts"), body)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.adjusted").value(2))
            .andExpect(jsonPath("$.data.unchanged").value(1))
            .andExpect(jsonPath("$.data.lines[?(@.inventoryId == '" + rowB + "')].inventoryTransactionId").value(hasItem(nullValue()))),
            "countId");
        call(get("/inventories/" + rowA)).andExpect(jsonPath("$.data.quantity").value(8));
        call(get("/inventories/" + rowB)).andExpect(jsonPath("$.data.quantity").value(5));
        call(get("/inventories/" + rowC)).andExpect(jsonPath("$.data.quantity").value(9));
        call(get("/inventory-transactions?projectId=" + DEMO_PROJECT + "&inventoryId=" + rowA))
            .andExpect(jsonPath("$.data[0].transactionType").value("adjustment"))
            .andExpect(jsonPath("$.data[0].quantityDelta").value(-2))
            .andExpect(jsonPath("$.data[0].referenceType").value("inventory_count"))
            .andExpect(jsonPath("$.data[0].referenceId").value(countId));

        // A retry answers with the same count and changes nothing.
        call(post("/inventory-counts"), body)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.countId").value(countId));
        call(get("/inventories/" + rowA)).andExpect(jsonPath("$.data.quantity").value(8));
    }

    @Test
    void oneBadLineStopsTheWholeCount() throws Exception {
        String item = item();
        String rowA = stock(item, "10");
        String reserved = stock(item, "6");
        call(post("/inventory-transactions"), "{\"inventoryId\":\"" + reserved + "\",\"transactionType\":\"reserve\",\"quantity\":4,"
            + "\"requestId\":\"" + UUID.randomUUID() + "\"}").andExpect(status().isOk());

        // Counting below what is reserved is refused, and the first line is rolled back with it.
        call(post("/inventory-counts"), count(UUID.randomUUID().toString(), line(rowA, "3", null) + "," + line(reserved, "2", null)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("below the 4 reserved")));
        call(get("/inventories/" + rowA)).andExpect(jsonPath("$.data.quantity").value(10));

        // Stock that moved since the count started is not overwritten.
        call(post("/inventory-counts"), count(UUID.randomUUID().toString(), line(rowA, "3", "9")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("changed from 9 to 10")));

        call(post("/inventory-counts"), count(UUID.randomUUID().toString(), line(rowA, "3", null) + "," + line(rowA, "4", null)))
            .andExpect(status().isBadRequest());
        call(post("/inventory-counts"), count(UUID.randomUUID().toString(), line(rowA, "-1", null)))
            .andExpect(status().isBadRequest());
        call(get("/inventories/" + rowA)).andExpect(jsonPath("$.data.quantity").value(10));
    }

    // ---- helpers ----

    private static String count(String requestId, String lines) {
        return "{\"projectId\":\"" + DEMO_PROJECT + "\",\"requestId\":\"" + requestId + "\",\"note\":\"shelf A\",\"lines\":[" + lines + "]}";
    }

    private static String line(String inventoryId, String counted, String expected) {
        return "{\"inventoryId\":\"" + inventoryId + "\",\"countedQuantity\":" + counted
            + (expected != null ? ",\"expectedQuantity\":" + expected : "") + "}";
    }

    private String stock(String itemId, String quantity) throws Exception {
        return id(call(post("/inventories"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemId\":\"" + itemId + "\",\"quantity\":"
            + quantity + ",\"location\":\"CT-" + suffix().substring(0, 8) + "\"}").andExpect(status().isOk()), "inventoryId");
    }

    private String item() {
        String id = "itm-count-" + suffix();
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
