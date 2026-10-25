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
import java.util.ArrayList;
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

/** A project's supplier list and what it asks of purchase orders (docs/domain/purchasing.md PO18-PO21), in a project of its own. */
@AutoConfigureMockMvc
class SupplierIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void onceAProjectListsSuppliersItsOrdersNameAnActiveOne() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String project = call(DEMO_OWNER, post("/projects"), Map.of("projectName", "Suppliers " + tag, "ownerId", DEMO_OWNER), 200)
            .path("projectId").asText();
        String flour = item(project, "SU-FLOUR-" + tag);

        // Without a list, an order names any supplier, as before.
        JsonNode early = call(DEMO_OWNER, post("/purchase-orders"), order(project, "Anyone Ltd", flour), 200);

        // A supplier has a name of its own in the project and a lead time of whole days from 0 to 365.
        call(DEMO_OWNER, post("/suppliers"), supplier(project, " ", null), 400);
        call(DEMO_OWNER, post("/suppliers"), supplier(project, "Mill Co", "-1"), 400);
        call(DEMO_OWNER, post("/suppliers"), supplier(project, "Mill Co", "366"), 400);
        call(DEMO_OWNER, post("/suppliers"), supplier(project, "Mill Co", "2.5"), 400);
        JsonNode mill = call(DEMO_OWNER, post("/suppliers"), supplier(project, " Mill Co ", "5"), 200);
        assertEquals("Mill Co", mill.path("supplierName").asText());
        assertEquals(5, mill.path("leadTimeDays").asInt());
        assertEquals("Kim 010-1234", mill.path("contact").asText());
        assertTrue(mill.path("active").asBoolean());
        assertEquals(0, mill.path("version").asLong());
        call(DEMO_OWNER, post("/suppliers"), supplier(project, "mill co", null), 409);
        JsonNode salt = call(DEMO_OWNER, post("/suppliers"), supplier(project, "Salt Works", null), 200);
        assertTrue(salt.path("leadTimeDays").isNull(), salt.toString());

        // Now an order names a listed supplier, spelled as the list spells it.
        call(DEMO_OWNER, post("/purchase-orders"), order(project, "Anyone Ltd", flour), 400);
        JsonNode draft = call(DEMO_OWNER, post("/purchase-orders"), order(project, "  mill CO ", flour), 200);
        assertEquals("Mill Co", draft.path("supplierName").asText());
        String draftPath = "/purchase-orders/" + draft.path("purchaseOrderId").asText();
        // An order written before the list keeps its name but is not ordered from a supplier the list lacks.
        call(DEMO_OWNER, post("/purchase-orders/" + early.path("purchaseOrderId").asText() + "/order"), null, 400);
        assertEquals("Anyone Ltd", call(DEMO_OWNER, get("/purchase-orders/" + early.path("purchaseOrderId").asText()), null, 200)
            .path("supplierName").asText());

        // Switched off from the version the editor loaded; a stale version is refused.
        String millPath = "/suppliers/" + mill.path("supplierId").asText();
        JsonNode off = call(DEMO_OWNER, put(millPath), change("Mill Co", false, 0), 200);
        assertFalse(off.path("active").asBoolean());
        assertEquals(1, off.path("version").asLong());
        call(DEMO_OWNER, put(millPath), change("Mill Co", true, 0), 409);
        call(DEMO_OWNER, post(draftPath + "/order"), null, 409);
        call(DEMO_OWNER, post("/purchase-orders"), order(project, "Mill Co", flour), 409);

        // A rename cannot take another supplier's name; orders keep the name they were given.
        call(DEMO_OWNER, put(millPath), change("salt works", true, 1), 409);
        JsonNode renamed = call(DEMO_OWNER, put(millPath), change("Mill & Co", true, 1), 200);
        assertEquals(2, renamed.path("version").asLong());
        assertEquals("Mill Co", call(DEMO_OWNER, get(draftPath), null, 200).path("supplierName").asText());
        call(DEMO_OWNER, post(draftPath + "/order"), null, 400);
        JsonNode ordered = call(DEMO_OWNER, post("/purchase-orders"), order(project, "mill & co", flour), 200);
        assertEquals("ordered", call(DEMO_OWNER, post("/purchase-orders/" + ordered.path("purchaseOrderId").asText() + "/order"), null, 200)
            .path("status").asText());

        // Listed by name; members read the list, only writers change it, outsiders do neither.
        String viewer = member(project, "viewer");
        JsonNode listed = call(viewer, get("/suppliers").param("projectId", project), null, 200);
        List<String> names = new ArrayList<>();
        listed.forEach(one -> names.add(one.path("supplierName").asText()));
        assertEquals(List.of("Mill & Co", "Salt Works"), names);
        call(viewer, post("/suppliers"), supplier(project, "Viewer Co", null), 403);
        call(viewer, put(millPath), change("Mill & Co", false, 2), 403);
        call("unrelated-user", get("/suppliers").param("projectId", project), null, 403);
    }

    private static Map<String, Object> supplier(String projectId, String name, String leadTimeDays) {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", projectId);
        body.put("supplierName", name);
        body.put("contact", "Kim 010-1234");
        if (leadTimeDays != null) body.put("leadTimeDays", new BigDecimal(leadTimeDays));
        return body;
    }

    private static Map<String, Object> change(String name, boolean active, long expectedVersion) {
        Map<String, Object> body = new HashMap<>();
        body.put("supplierName", name);
        body.put("contact", "Kim 010-1234");
        body.put("leadTimeDays", 5);
        body.put("active", active);
        body.put("expectedVersion", expectedVersion);
        return body;
    }

    private static Map<String, Object> order(String projectId, String supplier, String itemId) {
        return Map.of("projectId", projectId, "supplierName", supplier, "lines", List.of(Map.of("itemId", itemId, "quantity", 10)));
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
