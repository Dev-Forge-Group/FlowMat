package org.myweb.flowmat;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
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

/** Material needs of open work orders (docs/domain/material-requirements.md) against real Postgres. */
@AutoConfigureMockMvc
class MaterialRequirementIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void openOrdersAddUpPerMaterialAgainstUsableStock() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String bread = item("MR-BREAD-" + tag, "unit_ea");
        String flour = item("MR-FLOUR-" + tag, "unit_kg");
        String salt = item("MR-SALT-" + tag, "unit_kg");
        String bomId = id(call(post("/boms"), json(Map.of("projectId", DEMO_PROJECT, "targetItemId", bread, "bomName", "Bread " + tag,
            "baseQuantity", 10, "baseUnit", "ea"))), "bomId");
        call(post("/boms/" + bomId + "/lines"), json(Map.of("childItemId", flour, "quantity", 5, "unit", "kg"))).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/lines"), json(Map.of("childItemId", salt, "quantity", 200, "unit", "g"))).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/submit")).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/approve")).andExpect(status().isOk());

        String first = order(bomId, "MR first " + tag, 20, true);
        String second = order(bomId, "MR second " + tag, 10, true);
        String draft = order(bomId, "MR draft " + tag, 100, false);
        String flourStock = id(call(post("/inventories"), json(Map.of("projectId", DEMO_PROJECT, "itemId", flour, "quantity", 12,
            "location", "MR-" + tag))), "inventoryId");
        call(post("/inventories"), json(Map.of("projectId", DEMO_PROJECT, "itemId", salt, "quantity", 1, "location", "MR-" + tag)))
            .andExpect(status().isOk());

        // 20 + 10 bread: 15 kg flour against 12 (3 short), 0.6 kg salt against 1; the draft order does not count.
        String flourLine = "$.data.lines[?(@.itemId == '" + flour + "')]";
        String saltLine = "$.data.lines[?(@.itemId == '" + salt + "')]";
        call(get("/material-requirements").param("projectId", DEMO_PROJECT))
            .andExpect(status().isOk())
            .andExpect(jsonPath(flourLine + ".required").value(hasItem(15.0)))
            .andExpect(jsonPath(flourLine + ".usable").value(hasItem(12.0)))
            .andExpect(jsonPath(flourLine + ".shortage").value(hasItem(3.0)))
            .andExpect(jsonPath(flourLine + ".unit").value(hasItem("kg")))
            .andExpect(jsonPath(flourLine + ".orders[*].workOrderId").value(hasItem(first)))
            .andExpect(jsonPath(flourLine + ".orders[*].workOrderId").value(hasItem(second)))
            .andExpect(jsonPath(flourLine + ".orders[*].workOrderId").value(not(hasItem(draft))))
            .andExpect(jsonPath(saltLine + ".required").value(hasItem(0.6)))
            .andExpect(jsonPath(saltLine + ".shortage").value(hasItem(0.0)));

        // A run of the first order has already used 4 kg: stock is 8 now, and the first order needs 10 - 4 = 6 more.
        String runId = id(call(post("/production-runs/start"), json(Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "workOrderId", first, "plannedOutputQty", 8))), "productionRunId");
        call(post("/production-runs/" + runId + "/items"), json(Map.of("inventoryId", flourStock, "itemId", flour, "direction", "input",
            "plannedQty", 4, "actualQty", 4, "unit", "kg"))).andExpect(status().isOk());
        call(get("/material-requirements").param("projectId", DEMO_PROJECT))
            .andExpect(jsonPath(flourLine + ".required").value(hasItem(11.0)))
            .andExpect(jsonPath(flourLine + ".usable").value(hasItem(8.0)))
            .andExpect(jsonPath(flourLine + ".shortage").value(hasItem(3.0)))
            .andExpect(jsonPath(flourLine + ".orders[?(@.workOrderId == '" + first + "')].required").value(hasItem(6.0)));
        // Readiness of that order counts the same way.
        call(get("/work-orders/" + first + "/readiness"))
            .andExpect(jsonPath("$.data.materials[?(@.itemId == '" + flour + "')].requiredQuantity").value(hasItem(6.0)))
            .andExpect(jsonPath("$.data.materials[?(@.itemId == '" + flour + "')].shortageQuantity").value(hasItem(0.0)));

        mockMvc.perform(get("/material-requirements").param("projectId", DEMO_PROJECT)
                .header("Authorization", "Bearer " + jwtProvider.generateAccessToken("mrp-outsider")))
            .andExpect(status().isForbidden());
    }

    // ---- helpers ----

    private String order(String bomId, String title, int quantity, boolean approve) throws Exception {
        String orderId = id(call(post("/work-orders"), json(Map.of("projectId", DEMO_PROJECT, "workOrderTitle", title,
            "workflowId", DEMO_WORKFLOW, "targetQuantity", quantity, "bomId", bomId))), "workOrderId");
        if (approve) {
            call(post("/work-orders/" + orderId + "/approve")).andExpect(status().isOk());
        }
        return orderId;
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
