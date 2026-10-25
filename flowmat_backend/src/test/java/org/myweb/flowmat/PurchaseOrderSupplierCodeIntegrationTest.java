package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

/** Placing an order keeps the supplier's own codes on its lines (docs/domain/purchasing.md PO45), in a project of its own. */
@AutoConfigureMockMvc
class PurchaseOrderSupplierCodeIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;

    @Test
    void orderingKeepsTheCodeTheOrdersSupplierUsesForEachItem() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String project = call(post("/projects"), Map.of("projectName", "Supplier codes " + tag, "ownerId", DEMO_OWNER), 200)
            .path("projectId").asText();
        String mill = call(post("/suppliers"), Map.of("projectId", project, "supplierName", "Mill Co"), 200).path("supplierId").asText();
        String salt = call(post("/suppliers"), Map.of("projectId", project, "supplierName", "Salt Works"), 200).path("supplierId").asText();
        String flour = item(project, "SC-FLOUR-" + tag);
        String sugar = item(project, "SC-SUGAR-" + tag);
        String rock = item(project, "SC-ROCK-" + tag);
        // Flour is usually from Mill Co as MC-12, sugar from Mill Co without a code, rock salt from Salt Works as SW-1.
        long flourVersion = call(put("/supplier-items"), usual(project, flour, mill, "MC-12", null), 200).path("version").asLong();
        call(put("/supplier-items"), usual(project, sugar, mill, null, null), 200);
        call(put("/supplier-items"), usual(project, rock, salt, "SW-1", null), 200);

        // A draft keeps no codes; ordering from mill co (any case) keeps Mill Co's.
        JsonNode draft = call(post("/purchase-orders"), Map.of("projectId", project, "supplierName", "mill co",
            "lines", List.of(Map.of("itemId", flour, "quantity", 5), Map.of("itemId", sugar, "quantity", 2), Map.of("itemId", rock, "quantity", 1))), 200);
        for (JsonNode line : draft.path("lines")) {
            assertTrue(line.path("supplierItemCode").isNull(), line.toString());
        }
        String path = "/purchase-orders/" + draft.path("purchaseOrderId").asText();
        JsonNode ordered = call(post(path + "/order"), null, 200);
        assertEquals("MC-12", ordered.path("lines").get(0).path("supplierItemCode").asText());
        assertTrue(ordered.path("lines").get(1).path("supplierItemCode").isNull(), ordered.toString());
        assertTrue(ordered.path("lines").get(2).path("supplierItemCode").isNull(), "rock salt is usually from another supplier");

        // A later change of the usual code leaves the order as it was ordered.
        call(put("/supplier-items"), usual(project, flour, mill, "MC-99", flourVersion), 200);
        assertEquals("MC-12", call(get(path), null, 200).path("lines").get(0).path("supplierItemCode").asText());
    }

    private static Map<String, Object> usual(String projectId, String itemId, String supplierId, String code, Long expectedVersion) {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", projectId);
        body.put("itemId", itemId);
        body.put("supplierId", supplierId);
        body.put("supplierItemCode", code);
        body.put("expectedVersion", expectedVersion);
        return body;
    }

    private String item(String projectId, String code) throws Exception {
        return call(post("/items"), Map.of("projectId", projectId, "itemCode", code, "itemName", code.toLowerCase(),
            "itemType", "material", "unitId", "unit_kg"), 200).path("itemId").asText();
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
