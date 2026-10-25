package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
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

/** Where a purchase order is to arrive (docs/domain/purchasing.md PO39-PO40), in projects of their own. */
@AutoConfigureMockMvc
class PurchaseDeliverToIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;

    @Test
    void anOrderArrivesAtAListedPlaceOnceTheProjectListsAny() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Without a location list, any place as typed; empty for none.
        String open = project("Free places " + tag);
        String flour = item(open, "PD-FLOUR-" + tag);
        JsonNode dock = call(post("/purchase-orders"), order(open, flour, " Dock 2 "), 200);
        assertEquals("Dock 2", dock.path("deliverTo").asText());
        JsonNode cleared = call(put("/purchase-orders/" + dock.path("purchaseOrderId").asText()), order(open, flour, null), 200);
        assertTrue(cleared.path("deliverTo").isNull(), cleared.toString());

        // With one, a listed active place as the list spells it.
        String listed = project("Listed places " + tag);
        String salt = item(listed, "PD-SALT-" + tag);
        call(post("/storage-locations"), Map.of("projectId", listed, "locationCode", "WH-A", "locationType", "warehouse"), 200);
        assertEquals("WH-A", call(post("/purchase-orders"), order(listed, salt, "wh-a"), 200).path("deliverTo").asText());
        call(post("/purchase-orders"), order(listed, salt, "WH-Z"), 400);
        call(post("/purchase-orders"), order(listed, salt, "x".repeat(101)), 400);
    }

    private static Map<String, Object> order(String projectId, String itemId, String deliverTo) {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", projectId);
        body.put("supplierName", "Mill Co");
        body.put("lines", List.of(Map.of("itemId", itemId, "quantity", 10)));
        body.put("deliverTo", deliverTo);
        return body;
    }

    private String project(String name) throws Exception {
        return call(post("/projects"), Map.of("projectName", name, "ownerId", DEMO_OWNER), 200).path("projectId").asText();
    }

    private String item(String projectId, String code) throws Exception {
        return call(post("/items"), Map.of("projectId", projectId, "itemCode", code, "itemName", code.toLowerCase(), "itemType", "material",
            "unitId", "unit_kg"), 200).path("itemId").asText();
    }

    private JsonNode call(MockHttpServletRequestBuilder request, Object body, int expected) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        var response = mvc.perform(request).andReturn().getResponse();
        JsonNode parsed = mapper.readTree(response.getContentAsString());
        assertEquals(expected, response.getStatus(), parsed.path("message").asText());
        return parsed.path("data");
    }
}
