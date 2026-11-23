package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.HashMap;
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

/** Writing off expired LOT stock (docs/domain/lot-expiry.md "만료 재고 폐기") against real Postgres. */
@AutoConfigureMockMvc
class ExpiredWriteOffIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void expiredStockIsIssuedOnceAndTheLotClosedWhileQuarantinedStockStays() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String item = data(send(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", "WO-" + tag, "itemName", "cream",
            "unitId", "unit_kg", "lotManageYn", "Y", "unitCost", 2))).path("itemId").asText();
        String old = lot(item, "OLD-" + tag, LocalDate.now().minusDays(3));
        String oldA = stock(item, old, "WO-A-" + tag, 2);
        String oldB = stock(item, old, "WO-B-" + tag, 1);
        String held = lot(item, "HELD-" + tag, LocalDate.now().minusDays(1));
        String heldStock = stock(item, held, "WO-C-" + tag, 4);
        send(post("/lots/" + held + "/recall/quarantine"), Map.of("reason", "lab check")).andExpect(status().isOk());
        String fresh = lot(item, "FRESH-" + tag, LocalDate.now().plusDays(30));
        String freshStock = stock(item, fresh, "WO-D-" + tag, 6);

        // Naming a LOT that has not expired is refused.
        send(post("/lots/expired/write-off"), Map.of("projectId", DEMO_PROJECT, "lotIds", List.of(fresh), "closeLots", false,
                "requestId", UUID.randomUUID().toString()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("has not expired")));

        Map<String, Object> body = new HashMap<>(Map.of("projectId", DEMO_PROJECT, "lotIds", List.of(old, held), "closeLots", true,
            "note", "weekly clean-out", "requestId", UUID.randomUUID().toString()));
        send(post("/lots/expired/write-off"), body)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lots").value(2))
            .andExpect(jsonPath("$.data.value").value(6.0))
            .andExpect(jsonPath("$.data.lines[0].lotId").value(old))
            .andExpect(jsonPath("$.data.lines[0].writtenOff").value(3.0))
            .andExpect(jsonPath("$.data.lines[0].closed").value(true))
            .andExpect(jsonPath("$.data.lines[1].writtenOff").value(0))
            .andExpect(jsonPath("$.data.lines[1].closed").value(false))
            .andExpect(jsonPath("$.data.lines[1].note").value(containsString("quarantined")));
        quantity(oldA, 0.0);
        quantity(oldB, 0.0);
        quantity(heldStock, 4.0);
        quantity(freshStock, 6.0);
        mockMvc.perform(get("/lots/" + old).header("Authorization", bearer()))
            .andExpect(jsonPath("$.data.lotStatus").value("closed"));
        mockMvc.perform(get("/inventory-transactions").param("inventoryId", oldA).header("Authorization", bearer()))
            .andExpect(jsonPath("$.data[0].referenceType").value("expiry_write_off"));

        // The same request again returns what it did and moves nothing.
        send(post("/lots/expired/write-off"), body)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lots").value(1))
            .andExpect(jsonPath("$.data.lines[0].writtenOff").value(3.0));
        quantity(heldStock, 4.0);
    }

    private String lot(String item, String lotNo, LocalDate expiry) throws Exception {
        return data(send(post("/lots"), Map.of("projectId", DEMO_PROJECT, "itemId", item, "lotNo", lotNo,
            "expiryDate", expiry.toString()))).path("lotId").asText();
    }

    private String stock(String item, String lot, String location, int quantity) throws Exception {
        return data(send(post("/inventories"), Map.of("projectId", DEMO_PROJECT, "itemId", item, "quantity", quantity,
            "location", location, "lotId", lot))).path("inventoryId").asText();
    }

    private void quantity(String inventoryId, double expected) throws Exception {
        mockMvc.perform(get("/inventories/" + inventoryId).header("Authorization", bearer()))
            .andExpect(jsonPath("$.data.quantity").value(expected));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions send(MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)).header("Authorization", bearer()));
    }

    private String bearer() {
        return "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER);
    }
}
