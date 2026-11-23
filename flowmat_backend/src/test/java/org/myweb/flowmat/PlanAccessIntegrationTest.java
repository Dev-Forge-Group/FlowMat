package org.myweb.flowmat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Who may plan (docs/status/ROADMAP-2026-11-23.md §8 next month 2, a pilot's factories kept apart): a viewer of the project
 * reads a node's execution requirements and previews a plan but cannot change requirements or confirm; someone who only
 * belongs to another project can do none of it.
 */
@AutoConfigureMockMvc
class PlanAccessIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void viewersReadAndPreviewAndOtherProjectsSeeNothing() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String item = data(as(DEMO_OWNER, post("/items")), Map.of("projectId", DEMO_PROJECT, "itemCode", "PA-" + tag,
            "itemName", "pa " + tag, "unitId", "unit_ea")).path("itemId").asText();
        String workflow = data(as(DEMO_OWNER, post("/workflows")), Map.of("projectId", DEMO_PROJECT, "workflowName", "Access " + tag))
            .path("workflowId").asText();
        String node = data(as(DEMO_OWNER, post("/processes")), Map.of("workflowId", workflow, "processName", "Make", "nodeType", "process"))
            .path("processId").asText();
        String port = data(as(DEMO_OWNER, post("/process-ios")), Map.of("processId", node, "direction", "output", "itemId", item,
            "quantity", 1, "unit", "ea", "resourceType", "product")).path("processIoId").asText();
        String requirementsPath = "/processes/" + node + "/requirements";
        Map<String, Object> requirements = Map.of("requirements", List.of(Map.of("kind", "time", "amount", 5)));
        data(as(DEMO_OWNER, put(requirementsPath)), requirements);
        data(as(DEMO_OWNER, post("/workflows/" + workflow + "/revisions")), null);
        Map<String, Object> target = Map.of("target", Map.of("processIoId", port, "quantity", 3));
        JsonNode plan = data(as(DEMO_OWNER, post("/workflows/" + workflow + "/plan-previews")), target);
        Map<String, Object> confirm = Map.of("target", Map.of("processIoId", port, "quantity", 3),
            "basis", objectMapper.convertValue(plan.path("basis"), Map.class), "requestId", "pa-" + tag);

        String viewer = user("pa-viewer-" + tag);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
            UUID.randomUUID().toString(), DEMO_PROJECT, viewer, "viewer");
        call(as(viewer, get(requirementsPath)), null).andExpect(status().isOk());
        call(as(viewer, post("/workflows/" + workflow + "/plan-previews")), target).andExpect(status().isOk());
        call(as(viewer, put(requirementsPath)), requirements).andExpect(status().isForbidden());
        call(as(viewer, post("/workflows/" + workflow + "/plan-confirmations")), confirm).andExpect(status().isForbidden());

        // Owner of another project only.
        String other = user("pa-other-" + tag);
        data(as(other, post("/projects")), Map.of("projectName", "Other " + tag, "ownerId", other));
        call(as(other, get(requirementsPath)), null).andExpect(status().isForbidden());
        call(as(other, post("/workflows/" + workflow + "/plan-previews")), target).andExpect(status().isForbidden());
        call(as(other, put(requirementsPath)), requirements).andExpect(status().isForbidden());
        call(as(other, post("/workflows/" + workflow + "/plan-confirmations")), confirm).andExpect(status().isForbidden());
    }

    private String user(String id) {
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER);
        return id;
    }

    private MockHttpServletRequestBuilder as(String userId, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(userId));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(body));
        return mockMvc.perform(request);
    }

    private JsonNode data(MockHttpServletRequestBuilder request, Object body) throws Exception {
        String response = call(request, body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data");
    }
}
