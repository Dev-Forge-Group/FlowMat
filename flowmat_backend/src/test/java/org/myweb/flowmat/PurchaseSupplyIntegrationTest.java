package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** What is on order counts against open work order needs (docs/domain/purchasing.md PO8), on real Postgres. */
@AutoConfigureMockMvc
class PurchaseSupplyIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;

    @Test
    void orderedPurchasesCountAgainstOpenWorkOrderNeedsUntilTheyArrive() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String bread = data(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", "PS-BREAD-" + tag, "itemName", "bread " + tag,
            "itemType", "material", "unitId", "unit_ea")).path("itemId").asText();
        String flour = data(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", "PS-FLOUR-" + tag, "itemName", "flour " + tag,
            "itemType", "material", "unitId", "unit_kg")).path("itemId").asText();
        data(post("/inventories"), Map.of("projectId", DEMO_PROJECT, "itemId", flour, "quantity", 8, "location", "PS-A-" + tag));
        String bomId = data(post("/boms"), Map.of("projectId", DEMO_PROJECT, "targetItemId", bread, "bomName", "Bread " + tag,
            "baseQuantity", 10, "baseUnit", "ea")).path("bomId").asText();
        data(post("/boms/" + bomId + "/lines"), Map.of("childItemId", flour, "quantity", 5, "unit", "kg"));
        data(post("/boms/" + bomId + "/submit"), null);
        data(post("/boms/" + bomId + "/approve"), null);
        String order = data(post("/work-orders"), Map.of("projectId", DEMO_PROJECT, "workOrderTitle", "Bread " + tag, "workflowId", DEMO_WORKFLOW,
            "targetItemId", bread, "targetQuantity", 20, "bomId", bomId)).path("workOrderId").asText();
        data(post("/work-orders/" + order + "/approve"), null);

        // 20 breads need 10 kg; 8 are there.
        assertLine(flour, "8", "2", "0");
        JsonNode draft = data(post("/purchase-orders"), Map.of("projectId", DEMO_PROJECT, "supplierName", "Mill " + tag,
            "lines", List.of(Map.of("itemId", flour, "quantity", 5))));
        String path = "/purchase-orders/" + draft.path("purchaseOrderId").asText();
        // A draft is not ordered yet; once ordered, the 5 kg on their way cover the shortage.
        assertLine(flour, "8", "2", "0");
        JsonNode ordered = data(post(path + "/order"), null);
        assertLine(flour, "8", "0", "5");
        // 2 kg arrive: they are usable now and 3 are still to come.
        data(post(path + "/receipts"), Map.of("requestId", UUID.randomUUID().toString(), "lines",
            List.of(Map.of("lineId", ordered.path("lines").get(0).path("lineId").asText(), "quantity", 2, "location", "PS-A-" + tag))));
        assertLine(flour, "10", "0", "3");
        // The rest is not coming.
        data(post(path + "/close"), Map.of("reason", "Rest not coming"));
        assertLine(flour, "10", "0", "0");
    }

    private void assertLine(String itemId, String usable, String shortage, String onOrder) throws Exception {
        for (JsonNode line : data(get("/material-requirements").param("projectId", DEMO_PROJECT), null).path("lines")) {
            if (line.path("itemId").asText().equals(itemId)) {
                assertEquals(0, new BigDecimal(usable).compareTo(line.path("usable").decimalValue()), line.toString());
                assertEquals(0, new BigDecimal(shortage).compareTo(line.path("shortage").decimalValue()), line.toString());
                assertEquals(0, new BigDecimal(onOrder).compareTo(line.path("onOrder").decimalValue()), line.toString());
                return;
            }
        }
        throw new AssertionError("No need for " + itemId);
    }

    private JsonNode data(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        var response = mvc.perform(request).andReturn().getResponse();
        JsonNode parsed = mapper.readTree(response.getContentAsString());
        assertEquals(200, response.getStatus(), parsed.path("message").asText());
        return parsed.path("data");
    }
}
