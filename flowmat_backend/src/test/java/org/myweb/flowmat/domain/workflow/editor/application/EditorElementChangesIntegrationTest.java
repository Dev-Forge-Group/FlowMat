package org.myweb.flowmat.domain.workflow.editor.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.IntegrationTestSupport;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Element changes to the drawing layer (docs/architecture/adr/ADR-042-drawing-layer-element-changes.md): two editors
 * working on different shapes both keep their edits; one edited from an old version of a shape is refused whole, naming
 * it.
 */
@AutoConfigureMockMvc
class EditorElementChangesIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    private String workflowId;
    private String lee;

    @BeforeEach
    void twoShapes() throws Exception {
        workflowId = data(DEMO_OWNER, post("/workflows"), Map.of("projectId", DEMO_PROJECT,
            "workflowName", "Element changes " + UUID.randomUUID())).path("workflowId").asText();
        lee = member("editor");
        data(DEMO_OWNER, put("/workflows/" + workflowId + "/editor-document"), Map.of("expectedVersion", 0,
            "nextElementSeq", 3, "elements", List.of(shape("shape-1", 0, 1), shape("shape-2", 200, 2))));
    }

    @Test
    void editorsChangingDifferentShapesBothKeepTheirEdits() throws Exception {
        Map<String, Integer> versions = versions(document(DEMO_OWNER));
        // Both edited from the same document: the owner moves shape-1, lee moves shape-2 and adds shape-3.
        data(DEMO_OWNER, patch(path()), Map.of("changes", List.of(Map.of("element", shape("shape-1", 50, 1),
            "baseVersion", versions.get("shape-1")))));
        JsonNode after = data(lee, patch(path()), Map.of("nextElementSeq", 4, "changes", List.of(
            Map.of("element", shape("shape-2", 250, 2), "baseVersion", versions.get("shape-2")),
            Map.of("element", shape("shape-3", 400, 3)))));

        assertThat(x(after, "shape-1")).isEqualTo(50);
        assertThat(x(after, "shape-2")).isEqualTo(250);
        assertThat(x(after, "shape-3")).isEqualTo(400);
        assertThat(versions(after)).containsEntry("shape-1", versions.get("shape-1") + 1)
            .containsEntry("shape-2", versions.get("shape-2") + 1).containsEntry("shape-3", 1);
        assertThat(after.path("nextElementSeq").asInt()).isEqualTo(4);
        assertThat(after.path("version").asInt()).isEqualTo(3);
        assertThat(document(DEMO_OWNER)).isEqualTo(after);
    }

    @Test
    void aShapeEditedFromAnOldVersionIsRefusedWholeAndNamed() throws Exception {
        Map<String, Integer> versions = versions(document(DEMO_OWNER));
        data(DEMO_OWNER, patch(path()), Map.of("changes", List.of(Map.of("element", shape("shape-1", 50, 1),
            "baseVersion", versions.get("shape-1")))));
        JsonNode before = document(DEMO_OWNER);

        // Lee's edit of shape-1 started before the owner's; the move of shape-2 in the same request is not applied either.
        call(lee, patch(path()), Map.of("changes", List.of(
            Map.of("element", shape("shape-1", 75, 1), "baseVersion", versions.get("shape-1")),
            Map.of("element", shape("shape-2", 250, 2), "baseVersion", versions.get("shape-2")))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("shape-1 was changed by someone else."));
        assertThat(document(DEMO_OWNER)).isEqualTo(before);
    }

    @Test
    void additionsAndDeletionsMeetTheSameRules() throws Exception {
        Map<String, Integer> versions = versions(document(DEMO_OWNER));
        // An id another editor took first.
        call(lee, patch(path()), Map.of("changes", List.of(Map.of("element", shape("shape-2", 0, 9)))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("shape-2 was changed by someone else."));
        // A deletion from an old version, then from the current one, then again (already gone is fine).
        call(lee, patch(path()), Map.of("changes", List.of(Map.of("deleteId", "shape-1", "baseVersion", versions.get("shape-1") - 1))))
            .andExpect(status().isConflict());
        JsonNode deleted = data(lee, patch(path()), Map.of("changes", List.of(Map.of("deleteId", "shape-1",
            "baseVersion", versions.get("shape-1")))));
        assertThat(versions(deleted)).containsOnlyKeys("shape-2");
        data(lee, patch(path()), Map.of("changes", List.of(Map.of("deleteId", "shape-1", "baseVersion", versions.get("shape-1")))));
        // Replacing an element that is gone is a conflict.
        call(DEMO_OWNER, patch(path()), Map.of("changes", List.of(Map.of("element", shape("shape-1", 10, 1),
            "baseVersion", versions.get("shape-1"))))).andExpect(status().isConflict());
    }

    @Test
    void requestsAreChecked() throws Exception {
        call(DEMO_OWNER, patch(path()), Map.of("changes", List.of())).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("changes are required."));
        call(DEMO_OWNER, patch(path()), Map.of("changes", List.of(Map.of("element", shape("shape-9", 0, 9)),
            Map.of("deleteId", "shape-9", "baseVersion", 1)))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Change each element once: shape-9."));
        call(DEMO_OWNER, patch(path()), Map.of("changes", List.of(Map.of("deleteId", "shape-1"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("A deletion names the baseVersion it was made from."));
        Map<String, Object> both = new HashMap<>(Map.of("element", shape("shape-9", 0, 9), "deleteId", "shape-9"));
        call(DEMO_OWNER, patch(path()), Map.of("changes", List.of(both))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("A change has an element to add or replace, or a deleteId."));
        String viewer = member("viewer");
        call(viewer, patch(path()), Map.of("changes", List.of(Map.of("element", shape("shape-9", 0, 9)))))
            .andExpect(status().isForbidden());
    }

    private String path() {
        return "/workflows/" + workflowId + "/editor-document/elements";
    }

    private static Map<String, Object> shape(String id, double x, int order) {
        return Map.of("id", id, "type", "rectangle", "x", x, "y", 0, "width", 100, "height", 60, "order", order,
            "geometry", Map.of("cornerRadius", 0), "style", Map.of("fill", "#ffffff", "stroke", "#111111", "strokeWidth", 1));
    }

    private JsonNode document(String userId) throws Exception {
        return data(userId, get("/workflows/" + workflowId + "/editor-document"), null);
    }

    private static Map<String, Integer> versions(JsonNode document) {
        Map<String, Integer> versions = new HashMap<>();
        document.path("elements").forEach(element -> versions.put(element.path("id").asText(), element.path("version").asInt()));
        return versions;
    }

    private static double x(JsonNode document, String id) {
        for (JsonNode element : document.path("elements")) {
            if (id.equals(element.path("id").asText())) return element.path("x").asDouble();
        }
        throw new AssertionError(id + " is not in " + document);
    }

    /** A new member of the demo project with the given role. */
    private String member(String role) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
            UUID.randomUUID().toString(), DEMO_PROJECT, id, role);
        return id;
    }

    private ResultActions call(String userId, MockHttpServletRequestBuilder request, Object body) throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        }
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(userId)));
    }

    private JsonNode data(String userId, MockHttpServletRequestBuilder request, Object body) throws Exception {
        var response = call(userId, request, body).andReturn().getResponse();
        JsonNode parsed = mapper.readTree(response.getContentAsString());
        assertThat(response.getStatus()).as(parsed.path("message").asText()).isEqualTo(200);
        return parsed.path("data");
    }
}
