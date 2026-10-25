package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Who should count an open count plan (docs/domain/stock-count.md P9-P10), in a project of its own. */
@AutoConfigureMockMvc
class CountPlanAssigneeIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void anOpenPlanIsGivenToAMemberWhoHearsOfIt() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String project = call(DEMO_OWNER, post("/projects"), Map.of("projectName", "Count " + tag, "ownerId", DEMO_OWNER), 200)
            .path("projectId").asText();
        String item = call(DEMO_OWNER, post("/items"), Map.of("projectId", project, "itemCode", "CA-FLOUR-" + tag, "itemName", "flour",
            "itemType", "material", "unitId", "unit_kg"), 200).path("itemId").asText();
        String stock = call(DEMO_OWNER, post("/inventories"), Map.of("projectId", project, "itemId", item, "quantity", 5, "location", "WH-A"), 200)
            .path("inventoryId").asText();
        String editor = member(project, "editor");
        String viewer = member(project, "viewer");
        JsonNode plan = call(DEMO_OWNER, post("/inventory-count-plans"), Map.of("projectId", project, "requestId", UUID.randomUUID().toString(),
            "blind", true, "note", "Aisle 3", "inventoryIds", List.of(stock)), 200);
        String path = "/inventory-count-plans/" + plan.path("planId").asText();
        assertTrue(plan.path("assignedTo").isNull(), plan.toString());

        // Only a member of the project; only writers give it.
        call(DEMO_OWNER, post(path + "/assignee"), assignee("unrelated-user"), 400);
        call(viewer, post(path + "/assignee"), assignee(editor), 403);
        JsonNode given = call(DEMO_OWNER, post(path + "/assignee"), assignee(editor), 200);
        assertEquals(editor, given.path("assignedTo").asText());
        // The blind baseline stays with the owner.
        assertTrue(call(editor, get(path), null, 200).path("lines").get(0).path("baselineQuantity").isNull());
        List<JsonNode> heard = told(editor, plan.path("planId").asText());
        assertEquals(1, heard.size(), heard.toString());
        assertEquals("count_plan", heard.get(0).path("type").asText());
        assertEquals("Count plan Aisle 3 is yours", heard.get(0).path("title").asText());
        assertEquals("A blind count of 1 stock record.", heard.get(0).path("body").asText());

        // The same person again tells no one; taking it oneself tells no one; blank gives it to no one.
        call(DEMO_OWNER, post(path + "/assignee"), assignee(editor), 200);
        assertEquals(1, told(editor, plan.path("planId").asText()).size());
        call(DEMO_OWNER, post(path + "/assignee"), assignee(DEMO_OWNER), 200);
        assertEquals(0, told(DEMO_OWNER, plan.path("planId").asText()).size());
        assertTrue(call(editor, post(path + "/assignee"), assignee(" "), 200).path("assignedTo").isNull());

        // A submitted plan is no longer given.
        JsonNode line = plan.path("lines").get(0);
        call(editor, put(path + "/lines/" + line.path("lineId").asText()), Map.of("countedQuantity", 5,
            "expectedEntryVersion", line.path("entryVersion").asLong()), 200);
        call(editor, post(path + "/submit"), null, 200);
        call(DEMO_OWNER, post(path + "/assignee"), assignee(editor), 409);
    }

    private static Map<String, Object> assignee(String userId) {
        Map<String, Object> body = new HashMap<>();
        body.put("assignedTo", userId);
        return body;
    }

    /** The user's notifications about {@code refId}, newest first. */
    private List<JsonNode> told(String userId, String refId) throws Exception {
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode notification : call(userId, get("/notifications"), null, 200)) {
            if (refId.equals(notification.path("refId").asText())) {
                found.add(notification);
            }
        }
        return found;
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
