package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

/** An item cannot be deleted while stock or a live BOM still relies on it, against real Postgres. */
@AutoConfigureMockMvc
class ItemDeleteGuardIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ItemRepository itemRepository;

    @Test
    void stockAndBomsKeepAnItemUntilTheyAreGone() throws Exception {
        String flour = item("unit_kg");
        String bread = item("unit_ea");
        String row = id(call(post("/inventories"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemId\":\"" + flour
            + "\",\"quantity\":5,\"location\":\"DG-" + suffix().substring(0, 6) + "\"}").andExpect(status().isOk()), "inventoryId");
        String bomId = id(call(post("/boms"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"targetItemId\":\"" + bread
            + "\",\"bomName\":\"Loaf\",\"baseQuantity\":1,\"baseUnit\":\"ea\"}").andExpect(status().isOk()), "bomId");
        call(post("/boms/" + bomId + "/lines"), "{\"childItemId\":\"" + flour + "\",\"quantity\":1,\"unit\":\"kg\"}")
            .andExpect(status().isOk());
        call(post("/boms/" + bomId + "/submit")).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/approve")).andExpect(status().isOk());

        // Both reasons are given at once.
        call(delete("/items/" + flour))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("still has stock records")))
            .andExpect(jsonPath("$.message").value(containsString("in BOM Loaf v1 (approved)")));

        // Stock gone, BOM still there.
        call(post("/inventory-transactions"), "{\"inventoryId\":\"" + row + "\",\"transactionType\":\"issue\",\"quantity\":5,"
            + "\"requestId\":\"" + UUID.randomUUID() + "\"}").andExpect(status().isOk());
        call(delete("/inventories/" + row)).andExpect(status().isOk());
        call(delete("/items/" + flour))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("retire or delete those BOMs first")));

        // A retired BOM no longer holds the item; the product it made is free too.
        call(post("/boms/" + bomId + "/retire")).andExpect(status().isOk());
        call(delete("/items/" + flour)).andExpect(status().isOk());
        call(delete("/items/" + bread)).andExpect(status().isOk());
    }

    @Test
    void aDraftBomHoldsItsProductUntilTheDraftIsDeleted() throws Exception {
        String cake = item("unit_ea");
        String draft = id(call(post("/boms"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"targetItemId\":\"" + cake
            + "\",\"bomName\":\"Cake\",\"baseQuantity\":1,\"baseUnit\":\"ea\"}").andExpect(status().isOk()), "bomId");
        call(delete("/items/" + cake))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("in BOM Cake v1 (draft)")));
        call(delete("/boms/" + draft)).andExpect(status().isOk());
        call(delete("/items/" + cake)).andExpect(status().isOk());
    }

    // ---- helpers ----

    private String item(String unitId) {
        String id = "itm-dg-" + suffix();
        Item item = new Item();
        item.setItemId(id);
        item.setProjectId(DEMO_PROJECT);
        item.setItemCode(id.toUpperCase());
        item.setItemName(id);
        item.setItemType("material");
        item.setResourceCategory("material");
        item.setUnitId(unitId);
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
