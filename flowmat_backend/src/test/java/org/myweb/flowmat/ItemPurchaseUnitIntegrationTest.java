package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
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

/** The unit an item is bought in (docs/domain/item-details.md "구매 단위") through item.purchase_unit/conversion_rate. */
@AutoConfigureMockMvc
class ItemPurchaseUnitIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void aPurchaseUnitIsSetResizedAndCleared() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> body = new HashMap<>(Map.of("projectId", DEMO_PROJECT, "itemCode", "PU-" + tag, "itemName", "flour",
            "unitId", "unit_kg", "purchaseUnit", " bag ", "purchaseUnitQty", 25));
        String id = objectMapper.readTree(send(post("/items"), body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.purchaseUnit").value("bag"))
                .andExpect(jsonPath("$.data.purchaseUnitQty").value(25.0))
                .andReturn().getResponse().getContentAsString())
            .path("data").path("itemId").asText();

        // Other changes leave it alone; a quantity alone resizes the bag.
        send(put("/items/" + id), Map.of("itemName", "bread flour")).andExpect(jsonPath("$.data.purchaseUnit").value("bag"));
        send(put("/items/" + id), Map.of("purchaseUnitQty", 12.5))
            .andExpect(jsonPath("$.data.purchaseUnit").value("bag"))
            .andExpect(jsonPath("$.data.purchaseUnitQty").value(12.5));
        send(put("/items/" + id), Map.of("purchaseUnitQty", 0))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("more than 0")));
        // Blank goes back to buying in the stock unit.
        send(put("/items/" + id), Map.of("purchaseUnit", ""))
            .andExpect(jsonPath("$.data.purchaseUnit").value(nullValue()))
            .andExpect(jsonPath("$.data.purchaseUnitQty").value(nullValue()));
        send(put("/items/" + id), Map.of("purchaseUnitQty", 10))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("Give the purchase unit")));
        send(put("/items/" + id), Map.of("purchaseUnit", "sack"))
            .andExpect(jsonPath("$.data.purchaseUnit").value("sack"))
            .andExpect(jsonPath("$.data.purchaseUnitQty").value(1.0));
    }

    @Test
    void theImportSetsAndResizesThePurchaseUnit() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String code = "PUI-" + tag;
        Map<String, String> fresh = Map.of("itemCode", code, "itemName", "sugar", "purchaseUnit", "sack", "purchaseUnitQty", "20");
        send(post("/items/import"), Map.of("projectId", DEMO_PROJECT, "dryRun", false, "rows", java.util.List.of(fresh,
                Map.of("itemCode", "PUJ-" + tag, "itemName", "salt", "purchaseUnitQty", "5"))))
            .andExpect(jsonPath("$.data.applied").value(false))
            .andExpect(jsonPath("$.data.rows[1].message").value(containsString("Give the purchase unit")));
        send(post("/items/import"), Map.of("projectId", DEMO_PROJECT, "dryRun", false, "rows", java.util.List.of(fresh)))
            .andExpect(jsonPath("$.data.applied").value(true));
        // A quantity alone resizes the sack already set; the same values again change nothing.
        send(post("/items/import"), Map.of("projectId", DEMO_PROJECT, "dryRun", false, "rows",
                java.util.List.of(Map.of("itemCode", code, "purchaseUnitQty", "25"))))
            .andExpect(jsonPath("$.data.updated").value(1))
            .andExpect(jsonPath("$.data.rows[0].message").value("purchase unit quantity"));
        send(post("/items/import"), Map.of("projectId", DEMO_PROJECT, "dryRun", true, "rows",
                java.util.List.of(Map.of("itemCode", code, "purchaseUnit", "sack", "purchaseUnitQty", "25"))))
            .andExpect(jsonPath("$.data.unchanged").value(1));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/items").param("projectId", DEMO_PROJECT)
                .header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)))
            .andExpect(jsonPath("$.data[?(@.itemCode == '" + code + "')].purchaseUnit").value(org.hamcrest.Matchers.hasItem("sack")))
            .andExpect(jsonPath("$.data[?(@.itemCode == '" + code + "')].purchaseUnitQty").value(org.hamcrest.Matchers.hasItem(25.0)));
    }

    private ResultActions send(MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)).header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }
}
