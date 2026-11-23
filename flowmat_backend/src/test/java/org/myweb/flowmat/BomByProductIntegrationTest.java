package org.myweb.flowmat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
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

/** BOM by-product and waste lines (docs/domain/bom-by-products.md) against real Postgres. */
@AutoConfigureMockMvc
class BomByProductIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void byProductsAndWasteComeOutOfABatchWithoutBeingPlannedAsMaterials() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String juice = item("BP-JUICE-" + tag);
        String oranges = item("BP-ORANGE-" + tag);
        String peel = item("BP-PEEL-" + tag);
        String pulp = item("BP-PULP-" + tag);

        // 1 kg of juice takes 2 kg oranges and gives off 0.5 kg peel (sold) and 0.3 kg pulp (thrown away).
        String bomId = id(call(post("/boms"), json(Map.of("projectId", DEMO_PROJECT, "targetItemId", juice, "bomName", "Juice " + tag,
            "baseQuantity", 1, "baseUnit", "kg"))), "bomId");
        line(bomId, oranges, 2, null).andExpect(status().isOk());
        line(bomId, peel, 0.5, "by_product").andExpect(status().isOk());
        line(bomId, pulp, 0.3, "waste")
            .andExpect(jsonPath("$.data.lines[0].lineType").value("material"))
            .andExpect(jsonPath("$.data.lines[1].lineType").value("by_product"))
            .andExpect(jsonPath("$.data.lines[2].lineType").value("waste"));
        line(bomId, item("BP-X-" + tag), 1, "emission")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("lineType is material, by_product or waste."));
        call(post("/boms/" + bomId + "/submit")).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/approve")).andExpect(status().isOk());

        // 10 kg: 20 kg oranges to use; 5 kg peel and 3 kg pulp come out, and are not materials.
        call(get("/boms/" + bomId + "/requirements").param("quantity", "10"))
            .andExpect(jsonPath("$.data.lines.length()").value(1))
            .andExpect(jsonPath("$.data.lines[0].childItemId").value(oranges))
            .andExpect(jsonPath("$.data.lines[0].requiredItemQuantity").value(20))
            .andExpect(jsonPath("$.data.outputs.length()").value(2))
            .andExpect(jsonPath("$.data.outputs[0].itemId").value(peel))
            .andExpect(jsonPath("$.data.outputs[0].lineType").value("by_product"))
            .andExpect(jsonPath("$.data.outputs[0].itemQuantity").value(5))
            .andExpect(jsonPath("$.data.outputs[1].itemQuantity").value(3));

        // What stock can make depends on the oranges only; there is no peel or pulp in stock.
        call(post("/inventories"), json(Map.of("projectId", DEMO_PROJECT, "itemId", oranges, "quantity", 4, "location", "BP-" + tag)))
            .andExpect(status().isOk());
        call(get("/boms/" + bomId + "/buildable"))
            .andExpect(jsonPath("$.data.buildable").value(2))
            .andExpect(jsonPath("$.data.limitingItemId").value(oranges))
            .andExpect(jsonPath("$.data.lines.length()").value(1));

        // Where-used tells the BOMs that give an item off from those that use it.
        call(get("/boms/where-used").param("projectId", DEMO_PROJECT).param("itemId", peel))
            .andExpect(jsonPath("$.data[0].lineType").value("by_product"));

        // A by-product is not something the item is made from, so its own BOM may use the product without a loop.
        String peelBom = id(call(post("/boms"), json(Map.of("projectId", DEMO_PROJECT, "targetItemId", peel, "bomName", "Peel " + tag,
            "baseQuantity", 1, "baseUnit", "kg"))), "bomId");
        line(peelBom, juice, 1, null).andExpect(status().isOk());
        call(post("/boms/" + peelBom + "/submit")).andExpect(status().isOk());

        // A new revision keeps the line types; a run plans only the material.
        call(post("/boms/" + bomId + "/revisions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lines[1].lineType").value("by_product"))
            .andExpect(jsonPath("$.data.lines[2].lineType").value("waste"));
        String runId = id(call(post("/production-runs/start"), json(Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "targetItemId", juice, "plannedOutputQty", 10, "bomId", bomId))), "productionRunId");
        call(get("/production-runs/" + runId + "/items"))
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].itemId").value(oranges));
    }

    private ResultActions line(String bomId, String child, Number quantity, String lineType) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("childItemId", child, "quantity", quantity, "unit", "kg"));
        body.put("lineType", lineType);
        return call(post("/boms/" + bomId + "/lines"), json(body));
    }

    private String item(String code) throws Exception {
        return id(call(post("/items"), json(Map.of("projectId", DEMO_PROJECT, "itemCode", code, "itemName", code.toLowerCase(),
            "itemType", "material", "unitId", "unit_kg"))), "itemId");
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private String id(ResultActions result, String field) throws Exception {
        return data(result).path(field).asText();
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
