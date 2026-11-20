package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** A workflow's guided views (docs/architecture/adr/ADR-037-guided-views.md) against real Postgres. */
@AutoConfigureMockMvc
class GuidedViewIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void chaptersAreMadeChangedOrderedAndDeleted() throws Exception {
        String workflowId = workflow();
        String mix = process(workflowId, "Mix");
        String bake = process(workflowId, "Bake");
        String pack = process(workflowId, "Pack");

        JsonNode first = data(call(post(views(workflowId)), save(" Prepare ", "  ", List.of(mix, bake), null)));
        assertThat(first.path("label").asText()).isEqualTo("Prepare");
        assertThat(first.path("note").isNull()).isTrue();
        assertThat(first.path("sortOrder").asInt()).isZero();
        assertThat(first.path("version").asInt()).isEqualTo(1);
        String firstId = first.path("guidedViewId").asText();
        String secondId = data(call(post(views(workflowId)), save("Ship", "What leaves the line", List.of(pack), null)))
            .path("guidedViewId").asText();

        // A change needs the version it was made on; a stale one is refused and changes nothing.
        call(put(views(workflowId) + "/" + firstId), save("Prepare the dough", null, List.of(bake, mix), 1))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.version").value(2))
            .andExpect(jsonPath("$.data.focusProcessIds[0]").value(bake));
        call(put(views(workflowId) + "/" + firstId), save("Late", null, List.of(mix), 1))
            .andExpect(status().isConflict());
        call(put(views(workflowId) + "/" + firstId), save("No version", null, List.of(mix), null))
            .andExpect(status().isBadRequest());

        call(put(views(workflowId) + "/order"), Map.of("guidedViewIds", List.of(secondId, firstId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].guidedViewId").value(secondId))
            .andExpect(jsonPath("$.data[1].label").value("Prepare the dough"));
        call(put(views(workflowId) + "/order"), Map.of("guidedViewIds", List.of(secondId)))
            .andExpect(status().isBadRequest());

        call(delete(views(workflowId) + "/" + secondId)).andExpect(status().isOk());
        call(get(views(workflowId)))
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].guidedViewId").value(firstId))
            .andExpect(jsonPath("$.data[0].sortOrder").value(0));
        call(delete(views(workflowId) + "/" + secondId)).andExpect(status().isNotFound());
    }

    @Test
    void aChapterIsCheckedAgainstItsWorkflowAndTheLimits() throws Exception {
        String workflowId = workflow();
        String mix = process(workflowId, "Mix");
        String elsewhere = process(workflow(), "Elsewhere");

        call(post(views(workflowId)), save("", null, List.of(mix), null)).andExpect(status().isBadRequest());
        call(post(views(workflowId)), save("x".repeat(49), null, List.of(mix), null)).andExpect(status().isBadRequest());
        // Characters are counted as a reader counts them: 48 emoji are 48 characters.
        call(post(views(workflowId)), save("😀".repeat(48), null, List.of(mix), null)).andExpect(status().isOk());
        call(post(views(workflowId)), save("Long note", "n".repeat(141), List.of(mix), null)).andExpect(status().isBadRequest());
        call(post(views(workflowId)), save("Nothing", null, List.of(), null)).andExpect(status().isBadRequest());
        call(post(views(workflowId)), save("Twice", null, List.of(mix, mix), null))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("twice")));
        call(post(views(workflowId)), save("Other workflow", null, List.of(elsewhere), null))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("not in this workflow")));

        List<String> many = new ArrayList<>();
        for (int index = 0; index < 31; index++) many.add(process(workflowId, "P" + index));
        call(post(views(workflowId)), save("Too many", null, many, null)).andExpect(status().isBadRequest());
        call(post(views(workflowId)), save("Thirty", null, many.subList(0, 30), null)).andExpect(status().isOk());

        for (int index = 2; index < 8; index++) {
            call(post(views(workflowId)), save("Chapter " + index, null, List.of(mix), null)).andExpect(status().isOk());
        }
        call(post(views(workflowId)), save("Ninth", null, List.of(mix), null))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("at most 8")));
    }

    @Test
    void aDeletedProcessIsLeftOutWhenReadAndPublishingKeepsTheChapters() throws Exception {
        String workflowId = workflow();
        String mix = process(workflowId, "Mix");
        String bake = process(workflowId, "Bake");
        String viewId = data(call(post(views(workflowId)), save("Line", "Mix then bake", List.of(mix, bake), null)))
            .path("guidedViewId").asText();

        JsonNode revision = data(call(post("/workflows/" + workflowId + "/revisions")));
        assertThat(revision.path("snapshot").path("guidedViews").path(0).path("label").asText()).isEqualTo("Line");
        assertThat(revision.path("snapshot").path("guidedViews").path(0).path("focusProcessIds").size()).isEqualTo(2);

        call(delete("/processes/" + mix)).andExpect(status().isOk());
        call(get(views(workflowId)))
            .andExpect(jsonPath("$.data[0].focusProcessIds.length()").value(1))
            .andExpect(jsonPath("$.data[0].focusProcessIds[0]").value(bake));
        // The published revision keeps the chapter as it was, whatever happens to the draft after.
        call(put(views(workflowId) + "/" + viewId), save("Renamed", null, List.of(bake), 1)).andExpect(status().isOk());
        call(get("/workflows/" + workflowId + "/revisions/" + revision.path("workflowRevisionId").asText()))
            .andExpect(jsonPath("$.data.snapshot.guidedViews[0].label").value("Line"))
            .andExpect(jsonPath("$.data.snapshot.guidedViews[0].focusProcessIds.length()").value(2));

        // A workflow without chapters publishes without the field.
        String plain = workflow();
        process(plain, "Only");
        assertThat(data(call(post("/workflows/" + plain + "/revisions"))).path("snapshot").has("guidedViews")).isFalse();
    }

    @Test
    void aViewerReadsAndAnOutsiderDoesNot() throws Exception {
        String workflowId = workflow();
        String mix = process(workflowId, "Mix");
        call(post(views(workflowId)), save("Line", null, List.of(mix), null)).andExpect(status().isOk());
        for (String role : List.of("viewer", "outsider")) {
            String actor = "guided-" + role + "-" + UUID.randomUUID().toString().substring(0, 8);
            jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
                + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", actor, actor + "@test.local", DEMO_OWNER);
            if (role.equals("viewer")) {
                jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
                    UUID.randomUUID().toString(), DEMO_PROJECT, actor, role);
            }
            mockMvc.perform(get(views(workflowId)).header("Authorization", bearer(actor)))
                .andExpect(role.equals("viewer") ? status().isOk() : status().isForbidden());
            mockMvc.perform(post(views(workflowId)).header("Authorization", bearer(actor))
                    .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(save("Mine", null, List.of(mix), null))))
                .andExpect(status().isForbidden());
        }
    }

    private static String views(String workflowId) {
        return "/workflows/" + workflowId + "/guided-views";
    }

    private static Map<String, Object> save(String label, String note, List<String> focus, Integer version) {
        Map<String, Object> body = new HashMap<>();
        body.put("label", label);
        body.put("note", note);
        body.put("focusProcessIds", focus);
        body.put("version", version);
        return body;
    }

    private String workflow() throws Exception {
        return data(call(post("/workflows"), Map.of("projectId", DEMO_PROJECT, "workflowName", "Guided " + UUID.randomUUID())))
            .path("workflowId").asText();
    }

    private String process(String workflowId, String name) throws Exception {
        return data(call(post("/processes"), Map.of("workflowId", workflowId, "processName", name))).path("processId").asText();
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
            .path("data");
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", bearer(DEMO_OWNER)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private String bearer(String userId) {
        return "Bearer " + jwtProvider.generateAccessToken(userId);
    }
}
