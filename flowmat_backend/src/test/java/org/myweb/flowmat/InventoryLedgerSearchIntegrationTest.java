package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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

/** The server-side movement ledger (docs/domain/stock-ledger.md) against real Postgres. */
@AutoConfigureMockMvc
class InventoryLedgerSearchIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ItemRepository itemRepository;

    @Test
    void pagesWalkTheWholeFilteredLedgerNewestFirstWithoutRepeats() throws Exception {
        String item = item();
        String row = data(call(post("/inventories"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemId\":\"" + item
            + "\",\"quantity\":10,\"location\":\"LS-" + suffix().substring(0, 6) + "\"}")).path("inventoryId").asText();
        move(row, "issue", "1", "pick A");
        move(row, "issue", "1", "pick B");
        move(row, "issue", "1", "Urgent_pick 100%");
        move(row, "reserve", "2", null);

        List<String> all = ids(search("itemId", item, "limit", "500"));
        assertThat(all).hasSize(5);

        // Two at a time gives the same rows in the same order, then stops.
        List<String> paged = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = cursor == null ? search("itemId", item, "limit", "2") : search("itemId", item, "limit", "2", "cursor", cursor);
            page.path("items").forEach(tx -> paged.add(tx.path("inventoryTransactionId").asText()));
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
            pages++;
        } while (cursor != null && pages < 10);
        assertThat(pages).isEqualTo(3);
        assertThat(paged).containsExactlyElementsOf(all);

        assertThat(ids(search("itemId", item, "type", "issue"))).hasSize(3);
        assertThat(ids(search("itemId", item, "text", "PICK"))).hasSize(3);
        // "_" and "%" are matched literally, not as wildcards.
        assertThat(ids(search("itemId", item, "text", "urgent_"))).hasSize(1);
        assertThat(ids(search("itemId", item, "text", "100%"))).hasSize(1);
        assertThat(ids(search("itemId", item, "text", "pick_"))).isEmpty();

        OffsetDateTime now = OffsetDateTime.now();
        assertThat(ids(search("itemId", item, "from", now.minusHours(1).toString(), "to", now.plusHours(1).toString()))).hasSize(5);
        assertThat(ids(search("itemId", item, "from", now.plusHours(1).toString()))).isEmpty();
    }

    @Test
    void badPagingAndOutsidersAreRefused() throws Exception {
        call(get("/inventory-transactions/search").param("projectId", DEMO_PROJECT).param("cursor", "not-a-cursor"))
            .andExpect(status().isBadRequest());
        call(get("/inventory-transactions/search").param("projectId", DEMO_PROJECT).param("limit", "0"))
            .andExpect(status().isBadRequest());
        call(get("/inventory-transactions/search").param("projectId", DEMO_PROJECT).param("limit", "501"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/inventory-transactions/search").param("projectId", DEMO_PROJECT)
                .header("Authorization", "Bearer " + jwtProvider.generateAccessToken("ledger-outsider")))
            .andExpect(status().isForbidden());
    }

    // ---- helpers ----

    private JsonNode search(String... params) throws Exception {
        MockHttpServletRequestBuilder request = get("/inventory-transactions/search").param("projectId", DEMO_PROJECT);
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return data(call(request));
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("items").forEach(tx -> ids.add(tx.path("inventoryTransactionId").asText()));
        return ids;
    }

    private void move(String inventoryId, String type, String quantity, String note) throws Exception {
        call(post("/inventory-transactions"), "{\"inventoryId\":\"" + inventoryId + "\",\"transactionType\":\"" + type
            + "\",\"quantity\":" + quantity + ",\"requestId\":\"" + UUID.randomUUID() + "\""
            + (note != null ? ",\"note\":\"" + note + "\"" : "") + "}").andExpect(status().isOk());
    }

    private String item() {
        String id = "itm-ledger-" + suffix();
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

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
