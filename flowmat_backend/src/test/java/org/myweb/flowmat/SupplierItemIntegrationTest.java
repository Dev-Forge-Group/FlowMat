package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.HashMap;
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

/** Items' usual suppliers (docs/domain/purchasing.md PO29-PO31), in a project of their own. */
@AutoConfigureMockMvc
class SupplierItemIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void anItemHasOneUsualSupplierSetFromTheVersionTheEditorLoaded() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String project = project("Usual " + tag);
        String elsewhere = project("Elsewhere " + tag);
        String flour = item(project, "SI-FLOUR-" + tag);
        String foreign = item(elsewhere, "SI-OTHER-" + tag);
        String mill = call(DEMO_OWNER, post("/suppliers"), Map.of("projectId", project, "supplierName", "Mill Co", "leadTimeDays", 5), 200)
            .path("supplierId").asText();
        JsonNode gone = call(DEMO_OWNER, post("/suppliers"), Map.of("projectId", project, "supplierName", "Gone Co"), 200);
        call(DEMO_OWNER, put("/suppliers/" + gone.path("supplierId").asText()),
            Map.of("supplierName", "Gone Co", "active", false, "expectedVersion", 0), 200);
        String otherSupplier = call(DEMO_OWNER, post("/suppliers"), Map.of("projectId", elsewhere, "supplierName", "Far Co"), 200)
            .path("supplierId").asText();

        // An active supplier of the project for an item of the project.
        call(DEMO_OWNER, put("/supplier-items"), usual(project, foreign, mill, null, null), 400);
        call(DEMO_OWNER, put("/supplier-items"), usual(project, flour, otherSupplier, null, null), 400);
        call(DEMO_OWNER, put("/supplier-items"), usual(project, flour, gone.path("supplierId").asText(), null, null), 409);
        JsonNode set = call(DEMO_OWNER, put("/supplier-items"), usual(project, flour, mill, " MC-77 ", null), 200);
        assertEquals("Mill Co", set.path("supplierName").asText());
        assertEquals("MC-77", set.path("supplierItemCode").asText());
        assertEquals("SI-FLOUR-" + tag, set.path("itemCode").asText());
        assertTrue(set.path("supplierActive").asBoolean());
        assertEquals(0, set.path("version").asLong());

        // The same again is harmless; a change names the version it started from.
        assertEquals(0, call(DEMO_OWNER, put("/supplier-items"), usual(project, flour, mill, "MC-77", null), 200).path("version").asLong());
        call(DEMO_OWNER, put("/supplier-items"), usual(project, flour, mill, "MC-78", null), 409);
        assertEquals(1, call(DEMO_OWNER, put("/supplier-items"), usual(project, flour, mill, "MC-78", 0L), 200).path("version").asLong());
        JsonNode listed = call(DEMO_OWNER, get("/supplier-items").param("projectId", project), null, 200);
        assertEquals(1, listed.size());
        assertEquals("MC-78", listed.get(0).path("supplierItemCode").asText());

        // The supplier's own price, 0 or more with at most four decimals (PO38).
        Map<String, Object> priced = usual(project, flour, mill, "MC-78", 1L);
        priced.put("unitPrice", new BigDecimal("-1"));
        call(DEMO_OWNER, put("/supplier-items"), priced, 400);
        priced.put("unitPrice", new BigDecimal("1.00001"));
        call(DEMO_OWNER, put("/supplier-items"), priced, 400);
        priced.put("unitPrice", new BigDecimal("1.25"));
        JsonNode withPrice = call(DEMO_OWNER, put("/supplier-items"), priced, 200);
        assertEquals(2, withPrice.path("version").asLong());
        assertEquals(0, new BigDecimal("1.25").compareTo(withPrice.path("unitPrice").decimalValue()), withPrice.toString());
        assertEquals(2, call(DEMO_OWNER, put("/supplier-items"), priced, 200).path("version").asLong());

        // Members read; only writers change; clearing answers nothing.
        String viewer = member(project, "viewer");
        assertEquals(1, call(viewer, get("/supplier-items").param("projectId", project), null, 200).size());
        call(viewer, put("/supplier-items"), usual(project, flour, null, null, 1L), 403);
        call(DEMO_OWNER, put("/supplier-items"), usual(project, flour, null, null, 0L), 409);
        assertTrue(call(DEMO_OWNER, put("/supplier-items"), usual(project, flour, null, null, 2L), 200).isNull());
        assertEquals(0, call(DEMO_OWNER, get("/supplier-items").param("projectId", project), null, 200).size());
        call("unrelated-user", get("/supplier-items").param("projectId", project), null, 403);
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

    private String project(String name) throws Exception {
        return call(DEMO_OWNER, post("/projects"), Map.of("projectName", name, "ownerId", DEMO_OWNER), 200).path("projectId").asText();
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
