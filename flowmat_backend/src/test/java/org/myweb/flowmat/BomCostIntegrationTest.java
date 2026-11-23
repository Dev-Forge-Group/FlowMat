package org.myweb.flowmat;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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

/** Material cost on BOM requirements (docs/domain/material-cost.md) against real Postgres. */
@AutoConfigureMockMvc
class BomCostIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ItemRepository itemRepository;

    @Test
    void requirementsArePricedInEachMaterialsOwnUnit() throws Exception {
        String bread = item("unit_ea", null);
        String flour = item("unit_kg", "2");
        String salt = item("unit_kg", null);
        String bomId = id(call(post("/boms"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"targetItemId\":\"" + bread
            + "\",\"bomName\":\"Bread\",\"baseQuantity\":100,\"baseUnit\":\"ea\"}").andExpect(status().isOk()), "bomId");
        // 20,000 g of flour per 100 ea is 20 kg; flour costs 2 per kg.
        call(post("/boms/" + bomId + "/lines"), "{\"childItemId\":\"" + flour + "\",\"quantity\":20000,\"unit\":\"g\"}")
            .andExpect(status().isOk());
        call(post("/boms/" + bomId + "/lines"), "{\"childItemId\":\"" + salt + "\",\"quantity\":1,\"unit\":\"kg\"}")
            .andExpect(status().isOk());
        call(post("/boms/" + bomId + "/submit")).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/approve")).andExpect(status().isOk());

        // 250 ea: 50 kg flour × 2 = 100; salt has no cost, so the total is marked incomplete.
        call(get("/boms/" + bomId + "/requirements?quantity=250"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lines[?(@.childItemId == '" + flour + "')].requiredItemQuantity").value(hasItem(50.0)))
            .andExpect(jsonPath("$.data.lines[?(@.childItemId == '" + flour + "')].lineCost").value(hasItem(100.0)))
            .andExpect(jsonPath("$.data.lines[?(@.childItemId == '" + salt + "')].lineCost").value(hasItem(nullValue())))
            .andExpect(jsonPath("$.data.materialCost").value(100.0))
            .andExpect(jsonPath("$.data.costComplete").value(false));

        // A negative cost is refused; giving salt a cost completes the total: 2.5 kg × 0.5 = 1.25.
        call(put("/items/" + salt), "{\"unitCost\":-1}").andExpect(status().isBadRequest());
        call(put("/items/" + salt), "{\"unitCost\":0.5}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.unitCost").value(0.5));
        call(get("/boms/" + bomId + "/requirements?quantity=250"))
            .andExpect(jsonPath("$.data.materialCost").value(101.25))
            .andExpect(jsonPath("$.data.costComplete").value(true));
    }

    // ---- helpers ----

    private String item(String unitId, String unitCost) {
        String id = "itm-cost-" + suffix();
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
        item.setUnitCost(unitCost == null ? null : new BigDecimal(unitCost));
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
