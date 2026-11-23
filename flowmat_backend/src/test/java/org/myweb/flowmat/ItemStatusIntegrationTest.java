package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.catalog.domain.entity.UnitMaster;
import org.myweb.flowmat.domain.catalog.repository.UnitMasterRepository;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** What an item's status allows (docs/domain/item-status.md), against real Postgres. */
@AutoConfigureMockMvc
class ItemStatusIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UnitMasterRepository unitMasterRepository;

    @Test
    void aPhasedOutItemIsUsedUpButTakesNothingNew() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        send(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", "IS-X-" + tag, "itemName", "x", "itemStatus", "archived"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("is not one of: active, inactive, discontinued")));

        String flour = data(send(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", "IS-F-" + tag, "itemName", "flour",
            "unitId", "unit_kg", "itemStatus", " Active ", "safetyStockQty", 20))).path("itemId").asText();
        String bread = data(send(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", "IS-B-" + tag, "itemName", "bread",
            "unitId", "unit_ea"))).path("itemId").asText();
        String yeast = data(send(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", "IS-Y-" + tag, "itemName", "yeast",
            "unitId", "unit_kg", "lotManageYn", "Y"))).path("itemId").asText();
        String stock = data(send(post("/inventories"), Map.of("projectId", DEMO_PROJECT, "itemId", flour, "quantity", 10,
            "location", "IS-" + tag))).path("inventoryId").asText();
        String bom = data(send(post("/boms"), Map.of("projectId", DEMO_PROJECT, "targetItemId", bread, "bomName", "Bread " + tag,
            "baseQuantity", 1, "baseUnit", "ea"))).path("bomId").asText();
        send(post("/boms/" + bom + "/lines"), Map.of("childItemId", flour, "quantity", 1, "unit", "kg")).andExpect(status().isOk());
        String flourReorder = "$.data[?(@.itemId == '" + flour + "')]";
        mockMvc.perform(auth(get("/stock-alerts/reorder").param("projectId", DEMO_PROJECT)))
            .andExpect(jsonPath(flourReorder).isNotEmpty());

        send(put("/items/" + flour), Map.of("itemStatus", "Discontinued")).andExpect(jsonPath("$.data.itemStatus").value("discontinued"));
        send(put("/items/" + yeast), Map.of("itemStatus", "inactive")).andExpect(status().isOk());
        send(put("/items/" + flour), Map.of("itemStatus", "gone")).andExpect(status().isBadRequest());

        // Nothing new comes in, and it is no longer suggested for ordering.
        mockMvc.perform(auth(get("/stock-alerts/reorder").param("projectId", DEMO_PROJECT)))
            .andExpect(jsonPath(flourReorder).isEmpty());
        send(post("/inventories"), Map.of("projectId", DEMO_PROJECT, "itemId", flour, "quantity", 5, "location", "IS2-" + tag))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("IS-F-" + tag + " is discontinued; set it back to active to receive stock."));
        movement(stock, "receipt", 5).andExpect(status().isConflict());
        send(post("/inventories/import"), Map.of("projectId", DEMO_PROJECT, "dryRun", true,
                "rows", List.of(Map.of("itemCode", "IS-F-" + tag, "location", "IS-" + tag, "quantity", "1"))))
            .andExpect(jsonPath("$.data.rows[0].message").value(containsString("is discontinued")));
        send(post("/lots"), Map.of("projectId", DEMO_PROJECT, "itemId", yeast, "lotNo", "IS-L-" + tag))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("is inactive; set it back to active to register a LOT")));

        // No new plans: another BOM, a BOM for it, approving a BOM that uses it, a work order for it.
        String bun = data(send(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", "IS-U-" + tag, "itemName", "bun",
            "unitId", "unit_ea"))).path("itemId").asText();
        String bunBom = data(send(post("/boms"), Map.of("projectId", DEMO_PROJECT, "targetItemId", bun, "bomName", "Bun " + tag,
            "baseQuantity", 1, "baseUnit", "ea"))).path("bomId").asText();
        send(post("/boms/" + bunBom + "/lines"), Map.of("childItemId", flour, "quantity", 1, "unit", "kg"))
            .andExpect(status().isConflict());
        send(post("/boms"), Map.of("projectId", DEMO_PROJECT, "targetItemId", flour, "bomName", "Flour " + tag, "baseQuantity", 1,
            "baseUnit", "kg")).andExpect(status().isConflict());
        send(post("/boms/" + bom + "/submit"), Map.of())
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("IS-F-" + tag + " is discontinued")));
        send(post("/work-orders"), Map.of("projectId", DEMO_PROJECT, "workOrderTitle", "IS " + tag, "targetItemId", flour,
            "targetQuantity", 1)).andExpect(status().isConflict());

        // What is in stock is still used up.
        movement(stock, "issue", 4).andExpect(status().isOk());
        mockMvc.perform(auth(get("/inventories/" + stock))).andExpect(jsonPath("$.data.quantity").value(6));

        // Back to active, receiving works again.
        send(put("/items/" + flour), Map.of("itemStatus", "active")).andExpect(status().isOk());
        movement(stock, "receipt", 5).andExpect(status().isOk());
    }

    @Test
    void anItemKeepsAUnitDeactivatedSinceAndCanStillBeEdited() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase();
        UnitMaster sack = unit("sk" + tag);
        UnitMaster crate = unit("cr" + tag);
        String item = data(send(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", "IU-" + tag, "itemName", "rice",
            "unitId", sack.getUnitId()))).path("itemId").asText();
        sack.setActiveYn("N");
        crate.setActiveYn("N");
        unitMasterRepository.saveAll(List.of(sack, crate));

        // The form sends the unit back unchanged with every edit.
        send(put("/items/" + item), Map.of("itemName", "brown rice", "unitId", sack.getUnitId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.itemName").value("brown rice"))
            .andExpect(jsonPath("$.data.unitId").value(sack.getUnitId()));
        // Moving it to another inactive unit is still refused.
        send(put("/items/" + item), Map.of("unitId", crate.getUnitId()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("does not exist or is inactive")));
    }

    private UnitMaster unit(String code) {
        UnitMaster unit = new UnitMaster();
        unit.setUnitId("unit_" + code);
        unit.setUnitCode(code);
        unit.setUnitName(code);
        unit.setUnitType("count");
        unit.setConversionRate(BigDecimal.ONE);
        unit.setActiveYn("Y");
        return unitMasterRepository.save(unit);
    }

    private ResultActions movement(String inventoryId, String type, int quantity) throws Exception {
        return send(post("/inventory-transactions"), Map.of("inventoryId", inventoryId, "transactionType", type, "quantity", quantity,
            "requestId", UUID.randomUUID().toString()));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions send(MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        return mockMvc.perform(auth(request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))));
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER));
    }
}
