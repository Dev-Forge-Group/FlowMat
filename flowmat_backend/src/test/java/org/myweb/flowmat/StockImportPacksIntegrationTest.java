package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** Stock import in purchase units (docs/domain/stock-import.md, item-details.md "구매 단위") against real Postgres. */
@AutoConfigureMockMvc
class StockImportPacksIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void packsAreReceivedAsTheirStockUnits() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        send(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", "SP-" + tag, "itemName", "flour", "unitId", "unit_kg",
            "purchaseUnit", "bag", "purchaseUnitQty", 25)).andExpect(status().isOk());
        send(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", "SQ-" + tag, "itemName", "salt", "unitId", "unit_kg"))
            .andExpect(status().isOk());

        importRows(Map.of("itemCode", "SP-" + tag, "location", "SP-" + tag, "packs", "2", "quantity", "50"),
                Map.of("itemCode", "SQ-" + tag, "location", "SQ-" + tag, "packs", "3"),
                Map.of("itemCode", "SP-" + tag, "location", "SP-B-" + tag, "packs", "0"))
            .andExpect(jsonPath("$.data.applied").value(false))
            .andExpect(jsonPath("$.data.rows[0].message").value(containsString("not both")))
            .andExpect(jsonPath("$.data.rows[1].message").value(containsString("has no purchase unit")))
            .andExpect(jsonPath("$.data.rows[2].message").value(containsString("Packs must be greater than 0")));

        importRows(Map.of("itemCode", "SP-" + tag, "location", "SP-" + tag, "packs", "2.5"))
            .andExpect(jsonPath("$.data.applied").value(true));
        mockMvc.perform(get("/inventories").param("projectId", DEMO_PROJECT).header("Authorization", bearer()))
            .andExpect(jsonPath("$.data[?(@.location == 'SP-" + tag + "')].quantity").value(org.hamcrest.Matchers.hasItem(62.5)));
    }

    @SafeVarargs
    private ResultActions importRows(Map<String, String>... rows) throws Exception {
        return send(post("/inventories/import"), Map.of("projectId", DEMO_PROJECT, "dryRun", false, "rows", List.of(rows)));
    }

    private ResultActions send(MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)).header("Authorization", bearer()));
    }

    private String bearer() {
        return "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER);
    }
}
