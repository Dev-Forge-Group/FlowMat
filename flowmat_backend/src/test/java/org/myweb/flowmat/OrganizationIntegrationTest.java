package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
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
 * Organizations, ADR-001 Phase 1-3 (docs/domain/organization.md OR1-OR8) against real Postgres: the backfill, the default
 * organization of a new project, and that organization membership never reaches a project's data.
 */
@AutoConfigureMockMvc
class OrganizationIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtProvider jwt;
    @Autowired JdbcTemplate jdbc;

    @Test
    void theBackfillGivesEveryUserAPersonalOrganizationAndNewProjectsJoinIt() throws Exception {
        String personal = jdbc.queryForObject(
            "select organization_id from organization where owner_user_id = ? and organization_type = 'personal' and deleted_yn = 'N'",
            String.class, DEMO_OWNER);
        assertEquals(personal, jdbc.queryForObject("select organization_id from project where project_id = ?", String.class, DEMO_PROJECT));
        assertEquals("owner", jdbc.queryForObject(
            "select org_role from organization_member where organization_id = ? and user_id = ? and member_status = 'active'",
            String.class, personal, DEMO_OWNER));

        call(DEMO_OWNER, post("/projects"), Map.of("projectName", "Org default " + UUID.randomUUID(), "ownerId", DEMO_OWNER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.organizationId").value(personal));

        // A user created after the backfill gets a personal organization with their first project, and only one.
        String later = user();
        String first = data(call(later, post("/projects"), Map.of("projectName", "First " + UUID.randomUUID(), "ownerId", later)))
            .path("organizationId").asText();
        String second = data(call(later, post("/projects"), Map.of("projectName", "Second " + UUID.randomUUID(), "ownerId", later)))
            .path("organizationId").asText();
        assertEquals(first, second);
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from organization where owner_user_id = ? and organization_type = 'personal'", Integer.class, later));
    }

    @Test
    void organizationRolesNeverReachProjectData() throws Exception {
        String admin = user();
        String member = user();
        String outsider = user();
        String team = data(call(DEMO_OWNER, post("/organizations"), Map.of("organizationName", "Team kitchen")))
            .path("organizationId").asText();
        call(DEMO_OWNER, post("/organizations/" + team + "/members"), Map.of("userId", admin, "orgRole", "admin"))
            .andExpect(status().isOk());
        call(DEMO_OWNER, post("/organizations/" + team + "/members"), Map.of("userId", member)).andExpect(status().isOk());
        call(DEMO_OWNER, post("/organizations/" + team + "/members"), Map.of("userId", "no-such-user-" + UUID.randomUUID()))
            .andExpect(status().isBadRequest());
        call(DEMO_OWNER, post("/organizations/" + team + "/members"), Map.of("userId", member)).andExpect(status().isConflict());

        String project = data(call(DEMO_OWNER, post("/projects"), Map.of("projectName", "Team project " + UUID.randomUUID(),
            "ownerId", DEMO_OWNER, "organizationId", team))).path("projectId").asText();
        Map<String, Object> elsewhere = new HashMap<>(Map.of("projectName", "Not mine", "ownerId", outsider, "organizationId", team));
        call(outsider, post("/projects"), elsewhere).andExpect(status().isForbidden());

        // No override: an organization admin who is not a project member cannot read it or see it in their project list.
        call(admin, get("/projects/" + project), null).andExpect(status().isForbidden());
        call(admin, get("/projects"), null).andExpect(jsonPath("$.data[*].projectId").value(not(hasItem(project))));
        // Management metadata only, for owners and admins; members and outsiders see nothing.
        call(admin, get("/organizations/" + team + "/projects"), null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].projectId").value(project))
            .andExpect(jsonPath("$.data[0].ownerId").value(DEMO_OWNER));
        call(member, get("/organizations/" + team + "/projects"), null).andExpect(status().isForbidden());
        call(outsider, get("/organizations/" + team), null).andExpect(status().isForbidden());
        call(outsider, get("/organizations"), null).andExpect(jsonPath("$.data[*].organizationId").value(not(hasItem(team))));

        // Cross-project deny: a member of another project of the same organization cannot read this one.
        String other = data(call(DEMO_OWNER, post("/projects"), Map.of("projectName", "Other team project " + UUID.randomUUID(),
            "ownerId", DEMO_OWNER, "organizationId", team))).path("projectId").asText();
        projectMember(other, member, "editor");
        call(member, get("/projects/" + other), null).andExpect(status().isOk());
        call(member, get("/projects/" + project), null).andExpect(status().isForbidden());

        // Leaving removes only this organization's project memberships.
        projectMember(DEMO_PROJECT, member, "viewer");
        String membership = memberId(team, member);
        call(admin, post("/organizations/" + team + "/members/" + membership + "/leave"), null).andExpect(status().isForbidden());
        call(member, post("/organizations/" + team + "/members/" + membership + "/leave"), null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.memberStatus").value("left"));
        call(member, get("/projects/" + other), null).andExpect(status().isForbidden());
        call(member, get("/projects/" + DEMO_PROJECT), null).andExpect(status().isOk());

        // A project owner cannot leave before handing the project over; the last owner cannot be demoted.
        String ownerMembership = memberId(team, DEMO_OWNER);
        call(DEMO_OWNER, post("/organizations/" + team + "/members/" + ownerMembership + "/leave"), null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("owner")));
        call(DEMO_OWNER, put("/organizations/" + team + "/members/" + ownerMembership), Map.of("orgRole", "member"))
            .andExpect(status().isConflict());
        // Admins remove members only; owners remove anyone.
        String adminMembership = memberId(team, admin);
        call(admin, post("/organizations/" + team + "/members/" + ownerMembership + "/remove"), null).andExpect(status().isForbidden());
        call(DEMO_OWNER, post("/organizations/" + team + "/members/" + adminMembership + "/remove"), null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.memberStatus").value("removed"));
        call(admin, get("/organizations/" + team), null).andExpect(status().isForbidden());
    }

    private String memberId(String organizationId, String userId) {
        return jdbc.queryForObject("select organization_member_id from organization_member where organization_id = ? and user_id = ? and member_status = 'active'",
            String.class, organizationId, userId);
    }

    private void projectMember(String projectId, String userId, String role) {
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role,member_status,invited_by) values(?,?,?,?,'active',?)",
            UUID.randomUUID().toString(), projectId, userId, role, DEMO_OWNER);
    }

    private String user() {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?",
            id, id + "@test.local", DEMO_OWNER);
        return id;
    }

    private JsonNode data(ResultActions result) throws Exception {
        return mapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions call(String user, MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(user));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        return mvc.perform(request);
    }
}
