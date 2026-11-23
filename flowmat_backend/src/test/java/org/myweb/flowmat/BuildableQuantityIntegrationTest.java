package org.myweb.flowmat;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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

/** How much of a BOM's product usable stock could make now (docs/domain/material-requirements.md), against real Postgres. */
@AutoConfigureMockMvc
class BuildableQuantityIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void theScarcestMaterialLimitsTheProductRoundedDown() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String bread = item("BQ-BREAD-" + tag, "unit_ea");
        String dough = item("BQ-DOUGH-" + tag, "unit_kg");
        String flour = item("BQ-FLOUR-" + tag, "unit_kg");
        String salt = item("BQ-SALT-" + tag, "unit_kg");
        String yeast = item("BQ-YEAST-" + tag, "unit_kg");
        stock(flour, 12.4, tag);
        stock(salt, 1, tag);

        // 10 bread take 5 kg flour and 200 g salt: flour allows 24.8, salt 50, so 24 whole loaves, limited by flour.
        String breadBom = bom(bread, "Bread " + tag, 10, "ea");
        line(breadBom, flour, 5, "kg");
        line(breadBom, salt, 200, "g");
        String flourLine = "$.data.lines[?(@.childItemId == '" + flour + "')]";
        call(get("/boms/" + breadBom + "/buildable"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.targetUnit").value("ea"))
            .andExpect(jsonPath("$.data.baseQuantity").value(10))
            .andExpect(jsonPath("$.data.buildable").value(24))
            .andExpect(jsonPath("$.data.limitingItemId").value(flour))
            .andExpect(jsonPath(flourLine + ".perBatch").value(hasItem(5.0)))
            .andExpect(jsonPath(flourLine + ".usable").value(hasItem(12.4)))
            .andExpect(jsonPath("$.data.lines[?(@.childItemId == '" + salt + "')].buildable").value(hasItem(50)));

        // A product measured in kg keeps the part: 12.4 / 1.5 × 2 = 16.5333 kg. A material with no stock allows none.
        String doughBom = bom(dough, "Dough " + tag, 2, "kg");
        line(doughBom, flour, 1.5, "kg");
        call(get("/boms/" + doughBom + "/buildable"))
            .andExpect(jsonPath("$.data.buildable").value(16.5333));
        line(doughBom, yeast, 10, "g");
        call(get("/boms/" + doughBom + "/buildable"))
            .andExpect(jsonPath("$.data.buildable").value(0.0))
            .andExpect(jsonPath("$.data.limitingItemId").value(yeast));

        // The project-wide list has approved BOMs only, each worked out the same way.
        call(post("/boms/" + breadBom + "/submit")).andExpect(status().isOk());
        call(post("/boms/" + breadBom + "/approve")).andExpect(status().isOk());
        String breadEntry = "$.data[?(@.bomId == '" + breadBom + "')]";
        call(get("/boms/buildable").param("projectId", DEMO_PROJECT))
            .andExpect(status().isOk())
            .andExpect(jsonPath(breadEntry + ".buildable").value(hasItem(24)))
            .andExpect(jsonPath(breadEntry + ".limitingItemId").value(hasItem(flour)))
            .andExpect(jsonPath("$.data[?(@.bomId == '" + doughBom + "')]").isEmpty());

        // No materials, nothing to limit.
        String empty = bom(item("BQ-EMPTY-" + tag, "unit_ea"), "Empty " + tag, 1, "ea");
        call(get("/boms/" + empty + "/buildable"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.buildable").value(nullValue()))
            .andExpect(jsonPath("$.data.lines.length()").value(0));

        mockMvc.perform(get("/boms/" + breadBom + "/buildable")
                .header("Authorization", "Bearer " + jwtProvider.generateAccessToken("bq-outsider")))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/boms/buildable").param("projectId", DEMO_PROJECT)
                .header("Authorization", "Bearer " + jwtProvider.generateAccessToken("bq-outsider")))
            .andExpect(status().isForbidden());
    }

    @Test
    void repeatedMaterialLinesShareTheSameStockAfterUnitConversion() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String product = item("BQ-REPEAT-P-" + tag, "unit_ea");
        String flour = item("BQ-REPEAT-M-" + tag, "unit_kg");
        stock(flour, 10, tag);
        String bomId = bom(product, "Repeated flour " + tag, 10, "ea");
        line(bomId, flour, 5, "kg");
        line(bomId, flour, 2000, "g");
        // The by-product is an output, even when it is the same item as an input.
        call(post("/boms/" + bomId + "/lines"), json(Map.of("childItemId", flour, "quantity", 10,
            "unit", "kg", "lineType", "by_product"))).andExpect(status().isOk());
        call(get("/boms/" + bomId + "/buildable"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.buildable").value(14))
            .andExpect(jsonPath("$.data.limitingItemId").value(flour))
            .andExpect(jsonPath("$.data.lines.length()").value(1))
            .andExpect(jsonPath("$.data.lines[0].perBatch").value(7.0))
            .andExpect(jsonPath("$.data.lines[0].usable").value(10.0));
        // Draft calculations must be accurate; the existing approval contract still rejects repeated items.
        call(post("/boms/" + bomId + "/submit"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("appears more than once")));
    }

    @Test
    void repeatedMaterialNeedsAreRoundedPerBomLineBeforeTheyAreAdded() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String product = item("BQ-ROUND-P-" + tag, "unit_ea");
        String flour = item("BQ-ROUND-M-" + tag, "unit_kg");
        stock(flour, 0.0003, tag);
        String bomId = bom(product, "Rounded repeated flour " + tag, 1, "ea");
        // Each 0.06 g line needs 0.0001 kg at the BOM's stock precision.
        line(bomId, flour, 0.06, "g");
        line(bomId, flour, 0.06, "g");
        call(get("/boms/" + bomId + "/buildable"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.buildable").value(1))
            .andExpect(jsonPath("$.data.lines.length()").value(1))
            .andExpect(jsonPath("$.data.lines[0].perBatch").value(0.0002));
    }

    // ---- helpers ----

    private String bom(String target, String name, double base, String unit) throws Exception {
        return id(call(post("/boms"), json(Map.of("projectId", DEMO_PROJECT, "targetItemId", target, "bomName", name,
            "baseQuantity", base, "baseUnit", unit))), "bomId");
    }

    private void line(String bomId, String material, double quantity, String unit) throws Exception {
        call(post("/boms/" + bomId + "/lines"), json(Map.of("childItemId", material, "quantity", quantity, "unit", unit)))
            .andExpect(status().isOk());
    }

    private void stock(String itemId, double quantity, String tag) throws Exception {
        call(post("/inventories"), json(Map.of("projectId", DEMO_PROJECT, "itemId", itemId, "quantity", quantity,
            "location", "BQ-" + tag))).andExpect(status().isOk());
    }

    private String item(String code, String unitId) throws Exception {
        return id(call(post("/items"), json(Map.of("projectId", DEMO_PROJECT, "itemCode", code, "itemName", code.toLowerCase(),
            "unitId", unitId))), "itemId");
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String id(ResultActions result, String field) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
            .path("data").path(field).asText();
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
