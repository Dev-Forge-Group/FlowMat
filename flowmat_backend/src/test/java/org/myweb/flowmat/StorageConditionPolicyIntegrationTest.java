package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** A project that refuses stock in a place kept otherwise than the item (docs/domain/storage-location.md SL5-SL7), of its own. */
@AutoConfigureMockMvc
class StorageConditionPolicyIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void aProjectMayRefuseStockInAPlaceWithAnotherStorageCondition() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String project = call(DEMO_OWNER, post("/projects"), Map.of("projectName", "Conditions " + tag, "ownerId", DEMO_OWNER), 200)
            .path("projectId").asText();
        String cold = call(DEMO_OWNER, post("/storage-locations"), Map.of("projectId", project, "locationCode", "COLD", "locationType", "warehouse",
            "storageCondition", "Chilled"), 200).path("locationId").asText();
        call(DEMO_OWNER, post("/storage-locations"), Map.of("projectId", project, "locationCode", "B1", "locationType", "bin",
            "parentLocationId", cold), 200);
        call(DEMO_OWNER, post("/storage-locations"), Map.of("projectId", project, "locationCode", "DRY", "locationType", "warehouse"), 200);
        String fish = item(project, "SC-FISH-" + tag, "Frozen");
        String milk = item(project, "SC-MILK-" + tag, "  chilled ");
        String salt = item(project, "SC-SALT-" + tag, null);

        // Warned only, as before.
        JsonNode none = call(DEMO_OWNER, get("/inventory-settings").param("projectId", project), null, 200);
        assertEquals("warn", none.path("storageConditionPolicy").asText());
        assertTrue(none.path("version").isNull(), none.toString());
        stock(project, fish, "B1", 200);

        // Only the owner chooses; then fish is refused in B1, which is kept as COLD is.
        String editor = member(project, "editor");
        call(editor, put("/inventory-settings"), setting(project, "refuse", null), 403);
        call(DEMO_OWNER, put("/inventory-settings"), setting(project, "block", null), 400);
        assertEquals(0, call(DEMO_OWNER, put("/inventory-settings"), setting(project, "refuse", null), 200).path("version").asLong());
        assertEquals(0, call(DEMO_OWNER, put("/inventory-settings"), setting(project, "refuse", null), 200).path("version").asLong());
        call(DEMO_OWNER, put("/inventory-settings"), setting(project, "warn", null), 409);
        JsonNode refused = call(editor, post("/inventories"), Map.of("projectId", project, "itemId", fish, "quantity", 5, "location", "b1"), 409);
        assertTrue(refused.isMissingNode() || refused.isNull(), refused.toString());
        // Kept the same (spaces and case aside), no condition on either side, or a place without one: stock comes in.
        stock(project, milk, "B1", 200);
        stock(project, salt, "B1", 200);
        String dry = stock(project, fish, "DRY", 200);

        // A move into B1 is refused too; back to warning only, it goes.
        call(editor, post("/inventory-transfers"), Map.of("fromInventoryId", dry, "toLocation", "B1", "quantity", 1,
            "requestId", UUID.randomUUID().toString()), 409);
        assertEquals(1, call(DEMO_OWNER, put("/inventory-settings"), setting(project, "warn", 0L), 200).path("version").asLong());
        call(editor, post("/inventory-transfers"), Map.of("fromInventoryId", dry, "toLocation", "B1", "quantity", 1,
            "requestId", UUID.randomUUID().toString()), 200);
    }

    private String stock(String projectId, String itemId, String location, int expected) throws Exception {
        return call(DEMO_OWNER, post("/inventories"), Map.of("projectId", projectId, "itemId", itemId, "quantity", 5, "location", location), expected)
            .path("inventoryId").asText();
    }

    private String item(String projectId, String code, String condition) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("projectId", projectId, "itemCode", code, "itemName", code.toLowerCase(),
            "itemType", "material", "unitId", "unit_kg"));
        if (condition != null) body.put("details", Map.of("storageCondition", condition));
        return call(DEMO_OWNER, post("/items"), body, 200).path("itemId").asText();
    }

    private static Map<String, Object> setting(String projectId, String policy, Long expectedVersion) {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", projectId);
        body.put("storageConditionPolicy", policy);
        body.put("expectedVersion", expectedVersion);
        return body;
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
