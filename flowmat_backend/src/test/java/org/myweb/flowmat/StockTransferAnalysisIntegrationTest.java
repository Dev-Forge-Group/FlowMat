package org.myweb.flowmat;

import static org.hamcrest.Matchers.hasItem;
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

/** Moves between places (docs/domain/stock-analysis.md "위치 간 이동") against real Postgres. */
@AutoConfigureMockMvc
class StockTransferAnalysisIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void transfersAreAddedUpPerRouteAndItem() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String dock = "MV-DOCK-" + tag;
        String shelf = "MV-SHELF-" + tag;
        String line = "MV-LINE-" + tag;
        String bolt = data(call(post("/items"), json(Map.of("projectId", DEMO_PROJECT, "itemCode", "MVB-" + tag, "itemName", "bolt",
            "unitId", "unit_kg")))).path("itemId").asText();
        String nut = data(call(post("/items"), json(Map.of("projectId", DEMO_PROJECT, "itemCode", "MVN-" + tag, "itemName", "nut",
            "unitId", "unit_ea")))).path("itemId").asText();
        String boltAtDock = record(bolt, dock, 10);
        String nutAtDock = record(nut, dock, 20);

        // Dock to shelf: bolts twice (3 + 2) and nuts once (5). Shelf to line: bolts once (1).
        String boltAtShelf = move(boltAtDock, shelf, 3);
        move(boltAtDock, shelf, 2);
        move(nutAtDock, shelf, 5);
        move(boltAtShelf, line, 1);

        String dockToShelf = "$.data.routes[?(@.fromLocation == '" + dock + "' && @.toLocation == '" + shelf + "')]";
        String shelfToLine = "$.data.routes[?(@.fromLocation == '" + shelf + "' && @.toLocation == '" + line + "')]";
        call(get("/stock-analysis/transfers").param("projectId", DEMO_PROJECT).param("days", "7"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.days").value(7))
            .andExpect(jsonPath(dockToShelf + ".moves").value(hasItem(3)))
            // The busiest item first: bolts moved twice.
            .andExpect(jsonPath(dockToShelf + ".items[0].itemCode").value(hasItem("MVB-" + tag)))
            .andExpect(jsonPath(dockToShelf + ".items[0].quantity").value(hasItem(5.0)))
            .andExpect(jsonPath(dockToShelf + ".items[0].moves").value(hasItem(2)))
            .andExpect(jsonPath(dockToShelf + ".items[0].unit").value(hasItem("kg")))
            .andExpect(jsonPath(dockToShelf + ".items[1].itemCode").value(hasItem("MVN-" + tag)))
            .andExpect(jsonPath(dockToShelf + ".items[1].quantity").value(hasItem(5.0)))
            .andExpect(jsonPath(shelfToLine + ".moves").value(hasItem(1)))
            .andExpect(jsonPath(shelfToLine + ".items[0].quantity").value(hasItem(1.0)));

        call(get("/stock-analysis/transfers").param("projectId", DEMO_PROJECT).param("days", "0")).andExpect(status().isBadRequest());
        call(get("/stock-analysis/transfers")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/stock-analysis/transfers").param("projectId", DEMO_PROJECT)
                .header("Authorization", "Bearer " + jwtProvider.generateAccessToken("transfer-outsider")))
            .andExpect(status().isForbidden());
    }

    private String record(String itemId, String location, int quantity) throws Exception {
        return data(call(post("/inventories"), json(Map.of("projectId", DEMO_PROJECT, "itemId", itemId, "quantity", quantity,
            "location", location)))).path("inventoryId").asText();
    }

    /** Moves stock to a place; answers the stock record it went into. */
    private String move(String fromInventoryId, String toLocation, int quantity) throws Exception {
        return data(call(post("/inventory-transfers"), json(Map.of("fromInventoryId", fromInventoryId, "toLocation", toLocation,
            "quantity", quantity, "requestId", UUID.randomUUID().toString())))).path("in").path("inventoryId").asText();
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
