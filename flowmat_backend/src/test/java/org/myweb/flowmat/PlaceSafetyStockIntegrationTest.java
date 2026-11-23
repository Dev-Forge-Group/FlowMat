package org.myweb.flowmat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

/** Safety stock per place and the reorder list of one place (docs/domain/stock-alert.md PS1-PS6) against real Postgres. */
@AutoConfigureMockMvc
class PlaceSafetyStockIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ItemRepository itemRepository;

    @Test
    void aPlaceWatchesItsOwnSafetyStocksOverItsStockAndThePlacesInsideIt() throws Exception {
        // Its own project: once a project lists places, stock must sit at a listed place.
        String project = id(call(post("/projects"), "{\"projectName\":\"Place reorder " + suffix() + "\",\"ownerId\":\"" + DEMO_OWNER + "\"}"),
            "projectId");
        String warehouse = place(project, "WH-A", "warehouse", null);
        place(project, "BIN-A1", "bin", warehouse);
        String other = place(project, "WH-B", "warehouse", null);
        String bolt = item(project, "active");
        String nut = item(project, "active");
        String old = item(project, "inactive");
        stock(project, bolt, "10", "bin-a1");
        stock(project, bolt, "50", "WH-B");
        stock(project, nut, "3", "WH-A");

        // Setting needs a positive number, the project's item and place, both active (PS2).
        set(project, bolt, warehouse, "-1").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("The safety stock cannot be negative."));
        set(project, bolt, warehouse, "1.23456").andExpect(status().isBadRequest());
        set(project, "itm-nowhere", warehouse, "5").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("The item was not found in this project."));
        set(project, bolt, "loc-nowhere", "5").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("The place is not in this project's location list."));
        set(project, old, warehouse, "5").andExpect(status().isConflict());
        call(put("/stock-alerts/place-safety-stocks"), "{\"projectId\":\"" + project + "\",\"itemId\":\"" + bolt
            + "\",\"locationId\":\"" + warehouse + "\",\"safetyStockQty\":\"lots\"}").andExpect(status().isBadRequest());

        set(project, bolt, warehouse, "20").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].locationCode").value("WH-A"))
            .andExpect(jsonPath("$.data[0].safetyStockQty").value(20))
            .andExpect(jsonPath("$.data[0].updatedBy").value(DEMO_OWNER));
        set(project, nut, warehouse, "2").andExpect(status().isOk());
        set(project, bolt, other, "30").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.data[2].locationCode").value("WH-B"));

        // WH-A counts the bin inside it: bolt 10 < 20 is short, nut 3 >= 2 is not (PS4).
        reorder(project, "wh-a").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].itemId").value(bolt))
            .andExpect(jsonPath("$.data[0].availableQuantity").value(10))
            .andExpect(jsonPath("$.data[0].shortageQuantity").value(10))
            .andExpect(jsonPath("$.data[0].location").value("WH-A"));
        reorder(project, "WH-B").andExpect(jsonPath("$.data.length()").value(0));
        // The item-level list is unchanged: neither item has a safety stock of its own.
        call(get("/stock-alerts/reorder").param("projectId", project)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(0));
        reorder(project, "NOWHERE").andExpect(status().isBadRequest());

        // Changing and removing (zero) answer the list as it is now.
        set(project, bolt, warehouse, "5").andExpect(status().isOk());
        reorder(project, "WH-A").andExpect(jsonPath("$.data.length()").value(0));
        set(project, bolt, warehouse, "0").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2));
        call(get("/stock-alerts/place-safety-stocks").param("projectId", project)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.itemId == '" + bolt + "' && @.locationCode == 'WH-A')]").isEmpty());

        callAs("unrelated-user", get("/stock-alerts/place-safety-stocks").param("projectId", project)).andExpect(status().isForbidden());
        callAs("unrelated-user", put("/stock-alerts/place-safety-stocks").contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectId\":\"" + project + "\",\"itemId\":\"" + nut + "\",\"locationId\":\"" + warehouse
                + "\",\"safetyStockQty\":1}")).andExpect(status().isForbidden());
    }

    private ResultActions set(String project, String itemId, String locationId, String quantity) throws Exception {
        return call(put("/stock-alerts/place-safety-stocks"), "{\"projectId\":\"" + project + "\",\"itemId\":\"" + itemId
            + "\",\"locationId\":\"" + locationId + "\",\"safetyStockQty\":" + quantity + "}");
    }

    private ResultActions reorder(String project, String location) throws Exception {
        return call(get("/stock-alerts/reorder").param("projectId", project).param("location", location));
    }

    private String place(String project, String code, String type, String parent) throws Exception {
        return id(call(post("/storage-locations"), "{\"projectId\":\"" + project + "\",\"locationCode\":\"" + code
            + "\",\"locationType\":\"" + type + "\"" + (parent == null ? "" : ",\"parentLocationId\":\"" + parent + "\"") + "}")
            .andExpect(status().isOk()), "locationId");
    }

    private void stock(String project, String itemId, String quantity, String location) throws Exception {
        call(post("/inventories"), "{\"projectId\":\"" + project + "\",\"itemId\":\"" + itemId + "\",\"quantity\":"
            + quantity + ",\"location\":\"" + location + "\"}").andExpect(status().isOk());
    }

    private String item(String project, String itemStatus) {
        String id = "itm-psafe-" + suffix();
        Item item = new Item();
        item.setItemId(id);
        item.setProjectId(project);
        item.setItemCode(id.toUpperCase());
        item.setItemName(id);
        item.setItemType("material");
        item.setResourceCategory("material");
        item.setUnitId("unit_kg");
        item.setItemStatus(itemStatus);
        item.setLotManageYn("N");
        item.setDeletedYn("N");
        itemRepository.save(item);
        return id;
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return callAs(DEMO_OWNER, request);
    }

    private ResultActions callAs(String userId, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(userId)));
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
