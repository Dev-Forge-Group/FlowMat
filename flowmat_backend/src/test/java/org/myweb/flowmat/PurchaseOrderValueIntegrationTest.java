package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Unit prices and values of purchase order lines (docs/domain/purchasing.md PO14), on real Postgres in a project of their own. */
@AutoConfigureMockMvc
class PurchaseOrderValueIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void linesCarryAnOptionalUnitPriceThatAddsUpToTheOrderValue() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String project = call(DEMO_OWNER, post("/projects"), Map.of("projectName", "Prices " + tag, "ownerId", DEMO_OWNER), 200)
            .path("projectId").asText();
        String flour = item(project, "PV-FLOUR-" + tag);
        String salt = item(project, "PV-SALT-" + tag);

        // Below 0 or with five decimals is refused; empty is a price not agreed yet.
        call(DEMO_OWNER, post("/purchase-orders"), order(project, line(flour, "10", "-1")), 400);
        call(DEMO_OWNER, post("/purchase-orders"), order(project, line(flour, "10", "1.00001")), 400);
        JsonNode draft = call(DEMO_OWNER, post("/purchase-orders"), order(project, line(flour, "10", "1.25"), line(salt, "3", null)), 200);
        assertValue("12.5", draft.path("lines").get(0).path("lineValue"));
        assertTrue(draft.path("lines").get(1).path("lineValue").isNull(), draft.toString());
        assertValue("12.5", draft.path("orderValue"));
        assertFalse(draft.path("valueComplete").asBoolean());

        // Priced later, the value is complete; a free line counts as 0.
        String path = "/purchase-orders/" + draft.path("purchaseOrderId").asText();
        JsonNode priced = call(DEMO_OWNER, put(path), order(project, line(flour, "10", "1.25"), line(salt, "3", "0.3333")), 200);
        assertValue("0.9999", priced.path("lines").get(1).path("lineValue"));
        assertValue("13.4999", priced.path("orderValue"));
        assertTrue(priced.path("valueComplete").asBoolean());
        JsonNode free = call(DEMO_OWNER, put(path), order(project, line(flour, "10", "1.25"), line(salt, "3", "0")), 200);
        assertValue("12.5", free.path("orderValue"));
        assertTrue(free.path("valueComplete").asBoolean());

        // Project members read prices as they read item unit costs.
        String viewer = member(project, "viewer");
        JsonNode seen = call(viewer, get(path), null, 200);
        assertValue("1.25", seen.path("lines").get(0).path("unitPrice"));
    }

    private static void assertValue(String expected, JsonNode actual) {
        assertTrue(actual.isNumber(), String.valueOf(actual));
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()), actual.toString());
    }

    private static Map<String, Object> order(String projectId, Map<?, ?>... lines) {
        return Map.of("projectId", projectId, "supplierName", "Mill Co", "lines", List.of(lines));
    }

    private static Map<String, Object> line(String itemId, String quantity, String price) {
        Map<String, Object> line = new HashMap<>();
        line.put("itemId", itemId);
        line.put("quantity", new BigDecimal(quantity));
        if (price != null) line.put("unitPrice", new BigDecimal(price));
        return line;
    }

    private String item(String projectId, String code) throws Exception {
        return call(DEMO_OWNER, post("/items"), Map.of("projectId", projectId, "itemCode", code, "itemName", code.toLowerCase(),
            "itemType", "material", "unitId", "unit_kg"), 200).path("itemId").asText();
    }

    /** A new member of a project. */
    private String member(String projectId, String role) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
            UUID.randomUUID().toString(), projectId, id, role);
        return id;
    }

    private JsonNode call(String user, MockHttpServletRequestBuilder request, Object body, int expected) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(user));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        var response = mvc.perform(request).andReturn().getResponse();
        JsonNode parsed = mapper.readTree(response.getContentAsString());
        assertEquals(expected, response.getStatus(), parsed.path("message").asText());
        return parsed.path("data");
    }
}
